package com.example.apigateway.resilience

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.{Method, Response, Status}

import scala.concurrent.duration._

class RetryPolicySpec extends CatsEffectSuite:

  val retryPolicy = RetryPolicy[IO](
    RetryConfig(
      maxAttempts = 3,
      initialDelay = 10.millis
    )
  )

  test("Deve retentar método idempotente (GET) em status 502 até obter sucesso") {
    for {
      attemptsRef <- Ref.of[IO, Int](0)
      action = attemptsRef.modify { count =>
        val next = count + 1
        if next < 3 then (next, Response[IO](Status.BadGateway))
        else (next, Response[IO](Status.Ok))
      }
      resp <- retryPolicy.retry(Method.GET)(action)
      totalAttempts <- attemptsRef.get
    } yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(totalAttempts, 3)
    }
  }

  test("Não deve retentar método não idempotente (POST) mesmo em erro 502") {
    for {
      attemptsRef <- Ref.of[IO, Int](0)
      action = attemptsRef.modify { count =>
        val next = count + 1
        (next, Response[IO](Status.BadGateway))
      }
      resp <- retryPolicy.retry(Method.POST)(action)
      totalAttempts <- attemptsRef.get
    } yield {
      assertEquals(resp.status, Status.BadGateway)
      assertEquals(totalAttempts, 1) // Sem retry para POST
    }
  }

  test("Não deve retentar métodos idempotentes em status de erro de cliente (404)") {
    for {
      attemptsRef <- Ref.of[IO, Int](0)
      action = attemptsRef.modify { count =>
        val next = count + 1
        (next, Response[IO](Status.NotFound))
      }
      resp <- retryPolicy.retry(Method.GET)(action)
      totalAttempts <- attemptsRef.get
    } yield {
      assertEquals(resp.status, Status.NotFound)
      assertEquals(totalAttempts, 1)
    }
  }
