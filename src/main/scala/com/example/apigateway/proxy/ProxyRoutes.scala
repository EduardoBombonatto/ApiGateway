package com.example.apigateway.proxy

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all._
import com.example.apigateway.auth.AuthMiddleware
import com.example.apigateway.cache.RouteCache
import com.example.apigateway.observability.{AccessLog, MetricsService, TracingMiddleware}
import com.example.apigateway.ratelimit.RateLimitMiddleware
import com.example.apigateway.resilience.{CircuitBreaker, RetryPolicy}
import com.example.apigateway.routing.{MatchResult, RouteMatcher}
import org.http4s._
import org.http4s.client.Client
import org.typelevel.ci._

object ProxyRoutes:

  /** Constrói as rotas de proxy reverso integrando todo o pipeline de segurança, resiliência e observabilidade. */
  def apply[F[_]: Async](
      routeCache: RouteCache[F],
      reverseProxy: ReverseProxy[F],
      rateLimitMiddleware: Option[RateLimitMiddleware[F]] = None,
      authMiddleware: Option[AuthMiddleware[F]] = None,
      circuitBreaker: Option[CircuitBreaker[F]] = None,
      retryPolicy: Option[RetryPolicy[F]] = None,
      metricsService: Option[MetricsService[F]] = None
  ): HttpRoutes[F] =
    val tracing = TracingMiddleware[F]

    HttpRoutes[F] { req =>
      val (correlationId, reqWithTrace) = tracing.extractOrGenerate(req)
      val startTime = System.currentTimeMillis()

      OptionT.liftF(routeCache.getRoutes).flatMap { routes =>
        RouteMatcher.findMatch(routes, reqWithTrace) match
          case MatchResult.Matched(route) =>
            // 1. Autenticação e RBAC (JWT)
            val checkAuth = authMiddleware match
              case Some(am) => am.authenticate(route, reqWithTrace)
              case None     => (Right(reqWithTrace): Either[Response[F], Request[F]]).pure[F]

            val pipelineAction: F[Response[F]] = checkAuth.flatMap {
              case Left(authResp) =>
                // Falha de autenticação (401) ou permissão insuficiente (403)
                authResp.pure[F]

              case Right(enrichedReq) =>
                // 2. Rate Limiting (Redis / In-memory)
                val checkRateLimit = rateLimitMiddleware match
                  case Some(rlm) => rlm.checkLimit(route, enrichedReq)
                  case None      => (Right(None): Either[Response[F], Option[com.example.apigateway.ratelimit.RateLimitResult.Allowed]]).pure[F]

                checkRateLimit.flatMap {
                  case Left(rateLimitResp) =>
                    // Limite de taxa excedido: 429 retornado imediatamente
                    rateLimitResp.pure[F]

                  case Right(rateLimitAllowedOpt) =>
                    // 3. Encaminhamento resiliente com Retry e Circuit Breaker
                    val forwardAction: F[Response[F]] = retryPolicy match
                      case Some(rp) => rp.retry(req.method)(reverseProxy.forward(route, enrichedReq))
                      case None     => reverseProxy.forward(route, enrichedReq)

                    val fallbackResp: Option[F[Response[F]]] = route.fallbackUri.map { fbUri =>
                      val fbRoute = route.copy(targetUri = fbUri)
                      reverseProxy.forward(fbRoute, enrichedReq)
                    }

                    val resilientAction =
                      if route.circuitBreakerEnabled && circuitBreaker.isDefined then
                        circuitBreaker.get.protect(route.id, fallbackResp)(forwardAction)
                      else
                        forwardAction

                    resilientAction.map { resp =>
                      val withRateLimitHeaders = rateLimitAllowedOpt match
                        case Some(allowed) =>
                          resp.putHeaders(
                            Header.Raw(ci"X-RateLimit-Limit", allowed.limit.toString),
                            Header.Raw(ci"X-RateLimit-Remaining", allowed.remaining.toString)
                          )
                        case None => resp
                      withRateLimitHeaders
                    }
                }
            }

            // 4. Observabilidade: Coleta de métricas Prometheus e Log Estruturado
            val tracked = pipelineAction.flatMap { resp =>
              val durationMs = System.currentTimeMillis() - startTime
              val durationSec = durationMs / 1000.0

              val recordMetrics = metricsService match
                case Some(ms) =>
                  ms.recordRequest(route.id, req.method.name, resp.status.code, durationSec) >>
                    circuitBreaker.fold(().pure[F])(cb =>
                      cb.getState(route.id).flatMap(st => ms.recordCircuitBreakerState(route.id, st))
                    )
                case None =>
                  ().pure[F]

              val clientIp = req.headers
                .get(ci"x-forwarded-for")
                .map(_.head.value.split(",").head.trim)
                .orElse(req.remoteAddr.map(_.toString))
                .orElse(req.from.map(_.toInetAddress.getHostAddress))
                .getOrElse("unknown")

              val logAccess = Async[F].delay {
                AccessLog.log(
                  correlationId = correlationId,
                  clientIp = clientIp,
                  method = req.method.name,
                  path = req.uri.path.renderString,
                  routeId = route.id,
                  status = resp.status.code,
                  durationMs = durationMs,
                  upstream = route.targetUri.renderString
                )
              }

              recordMetrics >> logAccess.as(
                resp.putHeaders(Header.Raw(TracingMiddleware.CorrelationIdHeader, correlationId))
              )
            }

            OptionT.liftF(tracked)

          case MatchResult.MethodNotAllowed(allowedMethods) =>
            val allowedStr = allowedMethods.map(_.name).mkString(", ")
            val bodyJson =
              s"""{"error":"Method Not Allowed","allowedMethods":[${allowedMethods.map(m => s"\"${m.name}\"").mkString(",")}]}"""
            val resp = Response[F](Status.MethodNotAllowed)
              .putHeaders(
                Header.Raw(ci"Allow", allowedStr),
                Header.Raw(ci"Content-Type", "application/json"),
                Header.Raw(TracingMiddleware.CorrelationIdHeader, correlationId)
              )
              .withEntity(bodyJson)
            OptionT.pure[F](resp)

          case MatchResult.NotFound =>
            OptionT.none[F, Response[F]]
      }
    }

  /** Sobrecarga para compatibilidade com inicialização básica. */
  def apply[F[_]: Async](routeCache: RouteCache[F], client: Client[F]): HttpRoutes[F] =
    apply(routeCache, ReverseProxy[F](client))
