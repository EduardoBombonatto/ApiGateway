package com.example.apigateway.resilience

import cats.effect.{Async, Temporal}
import cats.syntax.all._
import org.http4s.{Method, Response, Status}

import java.io.IOException
import java.net.{ConnectException, UnknownHostException}
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Random

final case class RetryConfig(
    maxAttempts: Int = 3,
    initialDelay: FiniteDuration = 100.millis
)

/** Contrato funcional para política de retentativa com backoff exponencial e jitter. */
trait RetryPolicy[F[_]]:
  def retry(method: Method)(action: F[Response[F]]): F[Response[F]]

object RetryPolicy:

  private val IdempotentMethods: Set[Method] = Set(
    Method.GET,
    Method.HEAD,
    Method.PUT,
    Method.DELETE,
    Method.OPTIONS
  )

  def apply[F[_]: Async](config: RetryConfig = RetryConfig()): RetryPolicy[F] = new RetryPolicy[F]:
    override def retry(method: Method)(action: F[Response[F]]): F[Response[F]] =
      if !IdempotentMethods.contains(method) then
        action
      else
        loop(1, action)

    private def loop(attempt: Int, action: F[Response[F]]): F[Response[F]] =
      action.flatMap { resp =>
        if isRetryableStatus(resp.status) && attempt < config.maxAttempts then
          calculateBackoff(attempt).flatMap(delay => Temporal[F].sleep(delay) >> loop(attempt + 1, action))
        else
          resp.pure[F]
      }.handleErrorWith {
        case e: (IOException | ConnectException | UnknownHostException | TimeoutException)
            if attempt < config.maxAttempts =>
          calculateBackoff(attempt).flatMap(delay => Temporal[F].sleep(delay) >> loop(attempt + 1, action))

        case other =>
          Async[F].raiseError(other)
      }

    private def isRetryableStatus(status: Status): Boolean =
      status == Status.BadGateway || status == Status.GatewayTimeout || status == Status.ServiceUnavailable

    private def calculateBackoff(attempt: Int): F[FiniteDuration] =
      Async[F].delay {
        val multiplier = Math.pow(2.0, (attempt - 1).toDouble).toLong
        val baseMs = config.initialDelay.toMillis * multiplier
        val jitterMs = Random.nextLong(20L)
        (baseMs + jitterMs).millis
      }
