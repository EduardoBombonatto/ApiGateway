package com.example.apigateway.ratelimit

import cats.effect.IO
import com.example.apigateway.model.Route
import munit.CatsEffectSuite
import org.http4s._
import org.typelevel.ci._

class RateLimitMiddlewareSpec extends CatsEffectSuite:

  val routeWithoutLimit: Route = Route(
    id = "no-limit",
    prefix = Uri.Path.unsafeFromString("/public"),
    targetUri = Uri.unsafeFromString("http://localhost:8081"),
    rateLimitCapacity = None,
    rateLimitRefillRate = None
  )

  val limitedRoute: Route = Route(
    id = "limited",
    prefix = Uri.Path.unsafeFromString("/limited"),
    targetUri = Uri.unsafeFromString("http://localhost:8081"),
    rateLimitCapacity = Some(2),
    rateLimitRefillRate = Some(1)
  )

  test("Deve ignorar rotas sem configuração de rate limit") {
    for {
      limiter <- RateLimiter.inMemory[IO]
      resolver = KeyResolver[IO]
      middleware = RateLimitMiddleware[IO](limiter, resolver)
      req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/public/test"))
      res <- middleware.checkLimit(routeWithoutLimit, req)
    } yield {
      assertEquals(res, Right(None))
    }
  }

  test("Deve retornar 429 Too Many Requests com cabeçalhos apropriados ao esgotar cota") {
    for {
      limiter <- RateLimiter.inMemory[IO]
      resolver = KeyResolver[IO]
      middleware = RateLimitMiddleware[IO](limiter, resolver)
      req = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString("/limited/test"),
        headers = Headers(Header.Raw(ci"X-Forwarded-For", "192.168.1.100"))
      )
      r1 <- middleware.checkLimit(limitedRoute, req)
      r2 <- middleware.checkLimit(limitedRoute, req)
      r3 <- middleware.checkLimit(limitedRoute, req)
    } yield {
      assert(r1.isRight)
      assert(r2.isRight)
      r3 match
        case Left(resp) =>
          assertEquals(resp.status, Status.TooManyRequests)
          assertEquals(resp.headers.get(ci"x-ratelimit-limit").map(_.head.value), Some("2"))
          assertEquals(resp.headers.get(ci"x-ratelimit-remaining").map(_.head.value), Some("0"))
          assert(resp.headers.get(ci"retry-after").nonEmpty)
        case Right(_) =>
          fail("Deveria ter retornado 429 Too Many Requests")
    }
  }
