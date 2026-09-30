package com.example.apigateway.resilience

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{Response, Status}
import org.typelevel.ci._

import scala.concurrent.duration._

class CircuitBreakerSpec extends CatsEffectSuite:

  val config = CircuitBreakerConfig(
    failureThreshold = 3,
    openDurationMs = 200L // 200ms para testes rápidos
  )

  test("Deve manter o circuito Closed quando chamadas forem bem sucedidas (200)") {
    for {
      cb <- CircuitBreaker[IO](config)
      _ <- cb.protect("route-1")(IO.pure(Response[IO](Status.Ok)))
      _ <- cb.protect("route-1")(IO.pure(Response[IO](Status.Ok)))
      state <- cb.getState("route-1")
    } yield {
      assertEquals(state, CircuitBreakerState.Closed(0))
    }
  }

  test("Deve abrir o circuito após atingir o limiar de falhas (5xx)") {
    for {
      cb <- CircuitBreaker[IO](config)
      _ <- cb.protect("route-fail")(IO.pure(Response[IO](Status.BadGateway)))
      _ <- cb.protect("route-fail")(IO.pure(Response[IO](Status.BadGateway)))
      s2 <- cb.getState("route-fail")
      _ = assertEquals(s2, CircuitBreakerState.Closed(2))
      _ <- cb.protect("route-fail")(IO.pure(Response[IO](Status.BadGateway)))
      s3 <- cb.getState("route-fail")
    } yield {
      assert(s3.isInstanceOf[CircuitBreakerState.Open])
    }
  }

  test("Deve rejeitar imediatamente com 503 quando o circuito estiver Open") {
    for {
      cb <- CircuitBreaker[IO](config)
      // Atinge 3 falhas
      _ <- cb.protect("route-open")(IO.pure(Response[IO](Status.InternalServerError)))
      _ <- cb.protect("route-open")(IO.pure(Response[IO](Status.InternalServerError)))
      _ <- cb.protect("route-open")(IO.pure(Response[IO](Status.InternalServerError)))
      // Próxima chamada é bloqueada imediatamente
      resp <- cb.protect("route-open")(IO.pure(Response[IO](Status.Ok)))
    } yield {
      assertEquals(resp.status, Status.ServiceUnavailable)
      assertEquals(resp.headers.get(ci"x-circuit-breaker").map(_.head.value), Some("OPEN"))
    }
  }

  test("Deve executar fallback configurado quando o circuito estiver Open") {
    val fallbackResp = IO.pure(Response[IO](Status.Ok).withEntity("conteúdo de contingência"))
    for {
      cb <- CircuitBreaker[IO](config)
      _ <- cb.protect("route-fallback")(IO.pure(Response[IO](Status.BadGateway)))
      _ <- cb.protect("route-fallback")(IO.pure(Response[IO](Status.BadGateway)))
      _ <- cb.protect("route-fallback")(IO.pure(Response[IO](Status.BadGateway)))
      resp <- cb.protect("route-fallback", fallback = Some(fallbackResp))(IO.pure(Response[IO](Status.Ok)))
      body <- resp.as[String]
    } yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(body, "conteúdo de contingência")
    }
  }

  test("Deve transicionar para HalfOpen e fechar o circuito se o teste for bem sucedido") {
    for {
      cb <- CircuitBreaker[IO](config)
      // Atinge limiar de falhas
      _ <- cb.protect("route-recover")(IO.pure(Response[IO](Status.BadGateway)))
      _ <- cb.protect("route-recover")(IO.pure(Response[IO](Status.BadGateway)))
      _ <- cb.protect("route-recover")(IO.pure(Response[IO](Status.BadGateway)))
      // Aguarda janela de expiração (250ms > 200ms)
      _ <- IO.sleep(250.millis)
      // Chamada de teste (trial) bem sucedida
      resp <- cb.protect("route-recover")(IO.pure(Response[IO](Status.Ok)))
      finalState <- cb.getState("route-recover")
    } yield {
      assertEquals(resp.status, Status.Ok)
      assertEquals(finalState, CircuitBreakerState.Closed(0))
    }
  }
