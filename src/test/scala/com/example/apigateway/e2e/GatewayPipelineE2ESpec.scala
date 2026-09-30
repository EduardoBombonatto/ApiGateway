package com.example.apigateway.e2e

import cats.effect.{IO, Ref}
import com.example.apigateway.auth.{AuthMiddleware, AuthService}
import com.example.apigateway.cache.RouteCache
import com.example.apigateway.db.RouteRepository
import com.example.apigateway.model.Route
import com.example.apigateway.observability.{MetricsRoutes, MetricsService, TracingMiddleware}
import com.example.apigateway.proxy.{ProxyRoutes, ReverseProxy}
import com.example.apigateway.ratelimit.{KeyResolver, RateLimitMiddleware, RateLimiter}
import com.example.apigateway.resilience.{CircuitBreaker, CircuitBreakerConfig, RetryConfig, RetryPolicy}
import io.prometheus.client.CollectorRegistry
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.Router
import org.typelevel.ci._

import scala.concurrent.duration._

class GatewayPipelineE2ESpec extends CatsEffectSuite:

  val jwtSecret = "integration-test-secret-key-32-chars-long"
  val authService = AuthService[IO](jwtSecret, "gateway-test")
  val authMiddleware = AuthMiddleware[IO](authService)

  // Repositório simulado em memória
  def createInMemoryRepo(initialRoutes: List[Route]): IO[RouteRepository[IO]] =
    Ref.of[IO, Map[String, Route]](initialRoutes.map(r => r.id -> r).toMap).map { routesRef =>
      new RouteRepository[IO]:
        override def findAllActive: IO[List[Route]] = routesRef.get.map(_.values.toList)
        override def findAll: IO[List[com.example.apigateway.db.RouteRecord]] = IO.pure(Nil)
        override def findById(id: String): IO[Option[com.example.apigateway.db.RouteRecord]] = IO.pure(None)
        override def insert(record: com.example.apigateway.db.RouteRecord): IO[com.example.apigateway.db.RouteRecord] = IO.pure(record)
        override def update(id: String, record: com.example.apigateway.db.RouteRecord): IO[Option[com.example.apigateway.db.RouteRecord]] = IO.pure(Some(record))
        override def delete(id: String): IO[Boolean] = routesRef.modify { map =>
          if map.contains(id) then (map - id, true)
          else (map, false)
        }
    }

  test("End-to-End Pipeline: Auth (401/403/200), Rate Limit (429), Circuit Breaker (503/Fallback) e Métricas") {
    // 1. Upstream mock simulado
    val upstreamApp = HttpRoutes.of[IO] {
      case req @ GET -> Root / "orders" =>
        val userId = req.headers.get(ci"x-user-id").map(_.head.value).getOrElse("none")
        val roles = req.headers.get(ci"x-user-roles").map(_.head.value).getOrElse("none")
        val cid = req.headers.get(ci"x-correlation-id").map(_.head.value).getOrElse("none")
        Ok(s"""{"status":"ok","userId":"$userId","roles":"$roles","cid":"$cid"}""")

      case GET -> Root / "failing" =>
        BadGateway("""{"error":"Upstream Failure"}""")

      case GET -> Root / "contingency" =>
        Ok("""{"message":"serviço em contingência"}""")
    }

    val mockClient = Client.fromHttpApp(upstreamApp.orNotFound)

    val ordersRoute = Route(
      id = "orders-route",
      prefix = Uri.Path.unsafeFromString("/api/orders"),
      targetUri = Uri.unsafeFromString("http://upstream/orders"),
      stripPrefix = true,
      authRequired = true,
      requiredRoles = Set("admin"),
      rateLimitCapacity = Some(3),
      rateLimitRefillRate = Some(1),
      circuitBreakerEnabled = true,
      fallbackUri = None
    )

    val failingRoute = Route(
      id = "failing-route",
      prefix = Uri.Path.unsafeFromString("/api/fail"),
      targetUri = Uri.unsafeFromString("http://upstream/failing"),
      stripPrefix = true,
      authRequired = false,
      circuitBreakerEnabled = true,
      fallbackUri = Some(Uri.unsafeFromString("http://upstream/contingency"))
    )

    for {
      repo <- createInMemoryRepo(List(ordersRoute, failingRoute))
      routeCache <- RouteCache[IO](repo)
      reverseProxy = ReverseProxy[IO](mockClient)
      rateLimiter <- RateLimiter.inMemory[IO]
      keyResolver = KeyResolver[IO]
      rateLimitMiddleware = RateLimitMiddleware[IO](rateLimiter, keyResolver)
      circuitBreaker <- CircuitBreaker[IO](
        CircuitBreakerConfig(failureThreshold = 2, openDurationMs = 200L)
      )
      retryPolicy = RetryPolicy[IO](RetryConfig(maxAttempts = 2, initialDelay = 10.millis))
      registry = new CollectorRegistry()
      metricsService = MetricsService[IO](registry)
      metricsRoutes = MetricsRoutes[IO](metricsService)

      proxyApp = ProxyRoutes[IO](
        routeCache = routeCache,
        reverseProxy = reverseProxy,
        rateLimitMiddleware = Some(rateLimitMiddleware),
        authMiddleware = Some(authMiddleware),
        circuitBreaker = Some(circuitBreaker),
        retryPolicy = Some(retryPolicy),
        metricsService = Some(metricsService)
      )

      app = Router(
        "/metrics" -> metricsRoutes,
        "/" -> proxyApp
      ).orNotFound

      // Validação 1: Sem token -> 401 Unauthorized + Correlation ID
      req1 = Request[IO](method = Method.GET, uri = uri"/api/orders")
      resp1 <- app.run(req1)
      _ = assertEquals(resp1.status, Status.Unauthorized)
      _ = assert(resp1.headers.get(ci"x-correlation-id").nonEmpty)

      // Validação 2: Token com role errada -> 403 Forbidden
      userToken <- authService.generateToken("user-normal", Set("user"))
      req2 = Request[IO](
        method = Method.GET,
        uri = uri"/api/orders",
        headers = Headers(Header.Raw(ci"Authorization", s"Bearer $userToken"))
      )
      resp2 <- app.run(req2)
      _ = assertEquals(resp2.status, Status.Forbidden)

      // Validação 3: Token com role correta -> 200 OK + headers enriquecidos recebidos pelo upstream
      adminToken <- authService.generateToken("admin-boss", Set("admin", "billing"))
      req3 = Request[IO](
        method = Method.GET,
        uri = uri"/api/orders",
        headers = Headers(Header.Raw(ci"Authorization", s"Bearer $adminToken"))
      )
      resp3 <- app.run(req3)
      _ = assertEquals(resp3.status, Status.Ok)
      body3 <- resp3.as[String]
      _ = assert(body3.contains("admin-boss"))
      _ = assert(body3.contains("admin"))
      _ = assert(resp3.headers.get(ci"x-ratelimit-limit").map(_.head.value) == Some("3"))
      _ = assert(resp3.headers.get(ci"x-ratelimit-remaining").map(_.head.value) == Some("2"))

      // Validação 4: Estouro de rate limit -> 429 Too Many Requests
      _ <- app.run(req3) // remaining 1
      _ <- app.run(req3) // remaining 0
      respBurst <- app.run(req3) // 429!
      _ = assertEquals(respBurst.status, Status.TooManyRequests)
      _ = assert(respBurst.headers.get(ci"retry-after").nonEmpty)

      // Validação 5: Resiliência (Circuit Breaker + Fallback)
      reqFail = Request[IO](method = Method.GET, uri = uri"/api/fail")
      // 1ª e 2ª chamada falham com 502, estourando threshold (2)
      _ <- app.run(reqFail)
      _ <- app.run(reqFail)
      // 3ª chamada: circuito aberto aciona fallbackUri -> 200 com "serviço em contingência"
      respFallback <- app.run(reqFail)
      _ = assertEquals(respFallback.status, Status.Ok)
      bodyFallback <- respFallback.as[String]
      _ = assert(bodyFallback.contains("serviço em contingência"))

      // Validação 6: Observabilidade e raspagem de métricas no /metrics
      reqMetrics = Request[IO](method = Method.GET, uri = uri"/metrics")
      respMetrics <- app.run(reqMetrics)
      _ = assertEquals(respMetrics.status, Status.Ok)
      metricsBody <- respMetrics.as[String]
      _ = assert(metricsBody.contains("gateway_requests_total"))
      _ = assert(metricsBody.contains("orders-route"))
      _ = assert(metricsBody.contains("failing-route"))
      _ = assert(metricsBody.contains("gateway_circuit_breaker_state"))
    } yield ()
  }
