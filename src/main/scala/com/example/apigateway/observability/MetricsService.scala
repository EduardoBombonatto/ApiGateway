package com.example.apigateway.observability

import cats.effect.Sync
import cats.syntax.all._
import com.example.apigateway.resilience.CircuitBreakerState
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import io.prometheus.client.exporter.common.TextFormat

import java.io.StringWriter

/** Contrato funcional para coleta e exportação de métricas Prometheus. */
trait MetricsService[F[_]]:
  def recordRequest(routeId: String, method: String, status: Int, durationSeconds: Double): F[Unit]
  def recordCircuitBreakerState(routeId: String, state: CircuitBreakerState): F[Unit]
  def scrape(): F[String]

object MetricsService:

  def apply[F[_]: Sync](registry: CollectorRegistry = new CollectorRegistry()): MetricsService[F] =
    val requestsTotal = Counter.build()
      .name("gateway_requests_total")
      .help("Total de requisições HTTP processadas pelo Gateway")
      .labelNames("route", "method", "status")
      .register(registry)

    val requestDuration = Histogram.build()
      .name("gateway_request_duration_seconds")
      .help("Duração das requisições em segundos")
      .labelNames("route", "method")
      .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
      .register(registry)

    val circuitBreakerGauge = Gauge.build()
      .name("gateway_circuit_breaker_state")
      .help("Estado do Circuit Breaker por rota (0=Closed, 1=HalfOpen, 2=Open)")
      .labelNames("route")
      .register(registry)

    new MetricsService[F]:
      override def recordRequest(routeId: String, method: String, status: Int, durationSeconds: Double): F[Unit] =
        Sync[F].delay {
          requestsTotal.labels(routeId, method, status.toString).inc()
          requestDuration.labels(routeId, method).observe(durationSeconds)
        }

      override def recordCircuitBreakerState(routeId: String, state: CircuitBreakerState): F[Unit] =
        Sync[F].delay {
          val value = state match
            case CircuitBreakerState.Closed(_) => 0.0
            case CircuitBreakerState.HalfOpen  => 1.0
            case CircuitBreakerState.Open(_)   => 2.0
          circuitBreakerGauge.labels(routeId).set(value)
        }

      override def scrape(): F[String] =
        Sync[F].delay {
          val writer = new StringWriter()
          TextFormat.write004(writer, registry.metricFamilySamples())
          writer.toString
        }
