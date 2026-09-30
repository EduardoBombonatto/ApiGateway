package com.example.apigateway.resilience

import cats.effect.{Async, Ref}
import cats.syntax.all._
import org.http4s._
import org.typelevel.ci._

sealed trait CircuitBreakerState
object CircuitBreakerState:
  final case class Closed(failures: Int) extends CircuitBreakerState
  final case class Open(openedAt: Long) extends CircuitBreakerState
  case object HalfOpen extends CircuitBreakerState

final case class CircuitBreakerConfig(
    failureThreshold: Int = 5,
    openDurationMs: Long = 10000L
)

/** Contrato funcional para Circuit Breaker reativo por rota. */
trait CircuitBreaker[F[_]]:
  def protect(routeId: String, fallback: Option[F[Response[F]]] = None)(action: F[Response[F]]): F[Response[F]]
  def getState(routeId: String): F[CircuitBreakerState]
  def reset(routeId: String): F[Unit]

object CircuitBreaker:

  def apply[F[_]: Async](config: CircuitBreakerConfig = CircuitBreakerConfig()): F[CircuitBreaker[F]] =
    Ref.of[F, Map[String, CircuitBreakerState]](Map.empty).map { stateRef =>
      new CircuitBreaker[F]:

        override def getState(routeId: String): F[CircuitBreakerState] =
          stateRef.get.map(_.getOrElse(routeId, CircuitBreakerState.Closed(0)))

        override def reset(routeId: String): F[Unit] =
          stateRef.update(_.updated(routeId, CircuitBreakerState.Closed(0)))

        override def protect(routeId: String, fallback: Option[F[Response[F]]] = None)(
            action: F[Response[F]]
        ): F[Response[F]] =
          Async[F].delay(System.currentTimeMillis()).flatMap { now =>
            getState(routeId).flatMap {
              case CircuitBreakerState.Closed(failures) =>
                executeWithHandling(routeId, action, failures, isTrial = false, fallback)

              case CircuitBreakerState.Open(openedAt) =>
                if (now - openedAt) >= config.openDurationMs then
                  // Janela de espera expirou: transiciona para HalfOpen e testa recuperação
                  stateRef.update(_.updated(routeId, CircuitBreakerState.HalfOpen)) >>
                    executeWithHandling(routeId, action, failures = 0, isTrial = true, fallback)
                else
                  // Circuito ainda aberto: fail-fast imediato ou executa fallback
                  executeFallbackOrFastFail(routeId, fallback)

              case CircuitBreakerState.HalfOpen =>
                executeWithHandling(routeId, action, failures = 0, isTrial = true, fallback)
            }
          }

        private def executeWithHandling(
            routeId: String,
            action: F[Response[F]],
            failures: Int,
            isTrial: Boolean,
            fallback: Option[F[Response[F]]]
        ): F[Response[F]] =
          action
            .flatMap { resp =>
              if resp.status.isSuccess || resp.status.code < 500 then
                // Sucesso ou erro de cliente (4xx não é falha do upstream): reseta para Closed
                stateRef.update(_.updated(routeId, CircuitBreakerState.Closed(0))).as(resp)
              else
                // Resposta 5xx do upstream é contabilizada como falha
                recordFailure(routeId, failures, isTrial).flatMap { _ =>
                  fallback.getOrElse(resp.pure[F])
                }
            }
            .handleErrorWith { err =>
              recordFailure(routeId, failures, isTrial).flatMap { _ =>
                fallback match
                  case Some(fb) => fb
                  case None     => Async[F].raiseError(err)
              }
            }

        private def recordFailure(routeId: String, currentFailures: Int, isTrial: Boolean): F[Unit] =
          Async[F].delay(System.currentTimeMillis()).flatMap { now =>
            stateRef.update { states =>
              if isTrial then
                // Falha em HalfOpen reabre o circuito imediatamente
                states.updated(routeId, CircuitBreakerState.Open(now))
              else
                val nextFailures = currentFailures + 1
                if nextFailures >= config.failureThreshold then
                  states.updated(routeId, CircuitBreakerState.Open(now))
                else
                  states.updated(routeId, CircuitBreakerState.Closed(nextFailures))
            }
          }

        private def executeFallbackOrFastFail(
            routeId: String,
            fallback: Option[F[Response[F]]]
        ): F[Response[F]] =
          fallback match
            case Some(fb) => fb
            case None =>
              Response[F](Status.ServiceUnavailable)
                .putHeaders(
                  Header.Raw(ci"Content-Type", "application/json"),
                  Header.Raw(ci"X-Circuit-Breaker", "OPEN")
                )
                .withEntity(
                  s"""{"error":"Service Unavailable","message":"Circuito aberto para a rota '$routeId'. Requisições bloqueadas temporariamente."}"""
                )
                .pure[F]
    }
