package com.example.apigateway

import cats.effect.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import com.example.apigateway.admin.AdminRoutes
import com.example.apigateway.auth.{AuthMiddleware, AuthService}
import com.example.apigateway.cache.RouteCache
import com.example.apigateway.config.AppConfig
import com.example.apigateway.db.{DatabaseMigrator, RouteRepository}
import com.example.apigateway.discovery.{EurekaClient, LoadBalancer, ServiceRegistry}
import com.example.apigateway.observability.{MetricsRoutes, MetricsService}
import com.example.apigateway.proxy.{ProxyRoutes, ReverseProxy}
import com.example.apigateway.ratelimit.{KeyResolver, RateLimitMiddleware, RateLimiter}
import com.example.apigateway.resilience.{CircuitBreaker, CircuitBreakerConfig, RetryConfig, RetryPolicy}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.Logger

import scala.concurrent.duration._

object ApigatewayServer:

  def run[F[_]: Async]: F[Nothing] = {
    val config = AppConfig.load()

    for {
      // 1. Executa migrações Flyway no banco PostgreSQL
      _ <- Resource.eval(DatabaseMigrator.migrate[F](config.db))

      // 2. Cria pool de conexões HikariCP com Doobie
      ec <- ExecutionContexts.fixedThreadPool[F](config.db.poolSize)
      xa <- HikariTransactor.newHikariTransactor[F](
        "org.postgresql.Driver",
        config.db.url,
        config.db.user,
        config.db.password,
        ec
      )

      // 3. Inicializa Repositório e Cache Atômico em memória (pré-aquecido)
      routeRepository = RouteRepository[F](xa)
      routeCache <- Resource.eval(RouteCache[F](routeRepository))

      // 4. Inicializa o cliente HTTP assíncrono com pool de conexões
      client <- EmberClientBuilder.default[F].build

      // 5. Inicializa Service Discovery (Eureka), Cache de Instâncias e Load Balancer
      eurekaClient = EurekaClient[F](client, config.eureka)
      serviceRegistry <- Resource.eval(ServiceRegistry[F](eurekaClient, config.eureka))
      _ <- serviceRegistry.startBackgroundSync
      loadBalancer <- Resource.eval(LoadBalancer.roundRobin[F](serviceRegistry))

      // 6. Inicializa serviços de Segurança: JWT e RBAC
      authService = AuthService[F](config.auth.jwtSecret, config.auth.jwtIssuer)
      authMiddleware = AuthMiddleware[F](authService)

      // 7. Inicializa serviços de Rate Limiting (Redis com fallback em memória)
      rateLimiter <-
        if config.redis.enabled then RateLimiter.resource[F](config.redis.uri)
        else Resource.eval(RateLimiter.inMemory[F])
      keyResolver = KeyResolver[F]
      rateLimitMiddleware = RateLimitMiddleware[F](rateLimiter, keyResolver)

      // 8. Inicializa Resiliência: Circuit Breaker e RetryPolicy
      circuitBreaker <- Resource.eval(
        CircuitBreaker[F](
          CircuitBreakerConfig(
            failureThreshold = config.resilience.circuitBreakerFailureThreshold,
            openDurationMs = config.resilience.circuitBreakerOpenDurationMs
          )
        )
      )
      retryPolicy = RetryPolicy[F](
        RetryConfig(
          maxAttempts = config.resilience.retryMaxAttempts,
          initialDelay = config.resilience.retryInitialDelayMs.millis
        )
      )

      // 9. Inicializa Observabilidade: Métricas Prometheus
      metricsService = MetricsService[F]()
      metricsApp = MetricsRoutes[F](metricsService)

      // 10. Inicializa o serviço de ReverseProxy com suporte a Load Balancing (lb://)
      reverseProxy = ReverseProxy[F](client, loadBalancer)

      // 11. Monta as rotas combinadas do Gateway
      adminApp = AdminRoutes[F](routeRepository, routeCache, config.admin)
      proxyApp = ProxyRoutes[F](
        routeCache = routeCache,
        reverseProxy = reverseProxy,
        rateLimitMiddleware = Some(rateLimitMiddleware),
        authMiddleware = Some(authMiddleware),
        circuitBreaker = Some(circuitBreaker),
        retryPolicy = Some(retryPolicy),
        metricsService = Some(metricsService)
      )

      combinedRoutes = Router(
        "/admin" -> adminApp,
        "/metrics" -> metricsApp,
        "/" -> proxyApp
      ).orNotFound

      // Middleware de logging (preservando streaming do body)
      finalHttpApp = Logger.httpApp(logHeaders = true, logBody = false)(combinedRoutes)

      // 12. Inicializa o servidor Ember na porta 8080
      _ <-
        EmberServerBuilder.default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(finalHttpApp)
          .build
    } yield ()
  }.useForever
