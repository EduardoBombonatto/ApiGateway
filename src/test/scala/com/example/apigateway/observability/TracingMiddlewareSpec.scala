package com.example.apigateway.observability

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.dsl.io._
import org.typelevel.ci._

class TracingMiddlewareSpec extends CatsEffectSuite:

  val tracing = TracingMiddleware[IO]

  val echoApp: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "test" =>
      val cid = req.headers.get(ci"x-correlation-id").map(_.head.value).getOrElse("none")
      Ok(s"echo:$cid")
  }

  val tracedApp = tracing.trace(echoApp)

  test("Deve gerar novo Correlation ID quando ausente e devolver no header da resposta") {
    val req = Request[IO](method = Method.GET, uri = Uri.unsafeFromString("/test"))
    tracedApp.run(req).value.flatMap {
      case Some(resp) =>
        val cidHeader = resp.headers.get(ci"x-correlation-id")
        assert(cidHeader.nonEmpty)
        val cid = cidHeader.get.head.value
        assert(cid.length > 10)
        resp.as[String].map(body => assertEquals(body, s"echo:$cid"))
      case None =>
        fail("Esperava resposta mas obteve None")
    }
  }

  test("Deve preservar o Correlation ID existente quando já enviado pelo cliente") {
    val existingCid = "my-custom-trace-12345"
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("/test"),
      headers = Headers(Header.Raw(ci"x-correlation-id", existingCid))
    )
    tracedApp.run(req).value.flatMap {
      case Some(resp) =>
        val cidHeader = resp.headers.get(ci"x-correlation-id")
        assertEquals(cidHeader.map(_.head.value), Some(existingCid))
        resp.as[String].map(body => assertEquals(body, s"echo:$existingCid"))
      case None =>
        fail("Esperava resposta mas obteve None")
    }
  }
