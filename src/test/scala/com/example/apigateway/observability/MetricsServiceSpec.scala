package com.example.apigateway.observability

import cats.effect.IO
import com.example.apigateway.resilience.CircuitBreakerState
import io.prometheus.client.CollectorRegistry
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

class MetricsServiceSpec extends CatsEffectSuite:

  test("Deve registrar requisições, durações e estado do circuit breaker e exportar no formato Prometheus") {
    val registry = new CollectorRegistry()
    val metricsService = MetricsService[IO](registry)
    val routes = MetricsRoutes[IO](metricsService)

    for {
      _ <- metricsService.recordRequest("orders-route", "GET", 200, 0.045)
      _ <- metricsService.recordRequest("orders-route", "GET", 500, 0.120)
      _ <- metricsService.recordCircuitBreakerState("orders-route", CircuitBreakerState.Open(System.currentTimeMillis()))
      req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/metrics"))
      respOpt <- routes.run(req).value
      _ = assert(respOpt.nonEmpty)
      resp = respOpt.get
      _ = assertEquals(resp.status, Status.Ok)
      body <- resp.as[String]
    } yield {
      assert(body.contains("gateway_requests_total"))
      assert(body.contains("orders-route"))
      assert(body.contains("gateway_request_duration_seconds"))
      assert(body.contains("gateway_circuit_breaker_state"))
      assert(body.contains("2.0")) // Open = 2.0
    }
  }
