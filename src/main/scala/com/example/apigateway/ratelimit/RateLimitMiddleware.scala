package com.example.apigateway.ratelimit

import cats.Monad
import cats.syntax.all._
import com.example.apigateway.model.Route
import org.http4s._
import org.typelevel.ci._

/** Contrato funcional para interceptação e aplicação de rate limiting na borda. */
trait RateLimitMiddleware[F[_]]:
  def checkLimit(route: Route, req: Request[F]): F[Either[Response[F], Option[RateLimitResult.Allowed]]]

object RateLimitMiddleware:

  def apply[F[_]: Monad](
      rateLimiter: RateLimiter[F],
      keyResolver: KeyResolver[F]
  ): RateLimitMiddleware[F] = new RateLimitMiddleware[F]:
    override def checkLimit(route: Route, req: Request[F]): F[Either[Response[F], Option[RateLimitResult.Allowed]]] =
      (route.rateLimitCapacity, route.rateLimitRefillRate) match
        case (Some(capacity), Some(refillRate)) if capacity > 0 && refillRate > 0 =>
          keyResolver.resolveKey(route, req).flatMap { key =>
            rateLimiter.acquire(key, capacity, refillRate).map {
              case RateLimitResult.Allowed(limit, remaining) =>
                Right(Some(RateLimitResult.Allowed(limit, remaining)))

              case RateLimitResult.Rejected(limit, retryAfter) =>
                val body =
                  s"""{"error":"Too Many Requests","message":"Limite de requisições excedido. Tente novamente em $retryAfter segundo(s)."}"""
                val resp = Response[F](Status.TooManyRequests)
                  .putHeaders(
                    Header.Raw(ci"X-RateLimit-Limit", limit.toString),
                    Header.Raw(ci"X-RateLimit-Remaining", "0"),
                    Header.Raw(ci"Retry-After", retryAfter.toString),
                    Header.Raw(ci"Content-Type", "application/json")
                  )
                  .withEntity(body)
                Left(resp)
            }
          }

        case _ =>
          (Right(None): Either[Response[F], Option[RateLimitResult.Allowed]]).pure[F]
