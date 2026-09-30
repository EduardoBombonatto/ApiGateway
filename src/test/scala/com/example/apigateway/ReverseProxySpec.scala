package com.example.apigateway

import cats.effect.{IO, Ref}
import cats.syntax.all._
import com.example.apigateway.model.Route
import com.example.apigateway.proxy.{ProxyRoutes, ReverseProxy}
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._
import org.typelevel.ci._

import java.io.IOException
import java.util.concurrent.TimeoutException

class ReverseProxySpec extends CatsEffectSuite:

  val sampleRoute: Route = Route(
    id = "test-service",
    prefix = Uri.Path.unsafeFromString("/api/v1/service"),
    methods = Set(Method.GET, Method.POST),
    targetUri = uri"http://upstream-host:8080/base",
    stripPrefix = true
  )

  test("buildTargetUri deve remover prefixo e preservar query string quando stripPrefix = true") {
    val incoming = uri"/api/v1/service/items/99?sort=asc&limit=10"
    val result = ReverseProxy.buildTargetUri(sampleRoute, incoming)

    assertEquals(
      result.renderString,
      "http://upstream-host:8080/base/items/99?sort=asc&limit=10"
    )
  }

  test("buildTargetUri deve manter o path completo quando stripPrefix = false") {
    val routeNoStrip = sampleRoute.copy(stripPrefix = false, targetUri = uri"http://upstream-host:8080")
    val incoming = uri"/api/v1/service/items/99"
    val result = ReverseProxy.buildTargetUri(routeNoStrip, incoming)

    assertEquals(
      result.renderString,
      "http://upstream-host:8080/api/v1/service/items/99"
    )
  }

  test("ReverseProxy deve filtrar cabeçalhos hop-by-hop e injetar X-Forwarded-*") {
    for {
      capturedReqRef <- Ref.of[IO, Option[Request[IO]]](None)

      mockUpstreamApp = HttpApp[IO] { req =>
        capturedReqRef.set(Some(req)) *>
          Response[IO](Status.Ok)
            .putHeaders(
              Header.Raw(ci"connection", "close"),
              Header.Raw(ci"custom-upstream-header", "value123")
            )
            .withEntity("upstream-response")
            .pure[IO]
      }

      mockClient = Client.fromHttpApp(mockUpstreamApp)

      incomingReq = Request[IO](Method.GET, uri"/api/v1/service/hello")
        .putHeaders(
          Header.Raw(ci"connection", "keep-alive"),
          Header.Raw(ci"keep-alive", "timeout=5"),
          Header.Raw(ci"upgrade", "websocket"),
          Header.Raw(ci"host", "external-gateway.com:8080"),
          Header.Raw(ci"user-agent", "curl/7.68.0")
        )

      resp <- ReverseProxy.forward[IO](sampleRoute, incomingReq, mockClient)
      respBody <- resp.as[String]
      maybeCaptured <- capturedReqRef.get
    } yield {
      // 1. Resposta do upstream repassada
      assertEquals(resp.status, Status.Ok)
      assertEquals(respBody, "upstream-response")
      // 2. Headers hop-by-hop removidos da resposta
      assert(resp.headers.get(ci"connection").isEmpty)
      assertEquals(resp.headers.get(ci"custom-upstream-header").map(_.head.value), Some("value123"))

      // 3. Headers recebidos pelo upstream
      val upstreamReq = maybeCaptured.get
      assert(upstreamReq.headers.get(ci"connection").isEmpty)
      assert(upstreamReq.headers.get(ci"keep-alive").isEmpty)
      assert(upstreamReq.headers.get(ci"upgrade").isEmpty)
      assertEquals(upstreamReq.headers.get(ci"user-agent").map(_.head.value), Some("curl/7.68.0"))

      // 4. Injeção de X-Forwarded-*
      assertEquals(upstreamReq.headers.get(ci"x-forwarded-host").map(_.head.value), Some("external-gateway.com:8080"))
      assert(upstreamReq.headers.get(ci"x-forwarded-for").isDefined)
      assert(upstreamReq.headers.get(ci"x-forwarded-proto").isDefined)
    }
  }

  test("ReverseProxy deve transmitir o corpo da requisição em streaming sem perdas") {
    val requestPayload = """{"message":"teste de streaming em chunks de dados"}"""

    for {
      capturedBodyRef <- Ref.of[IO, String]("")

      mockUpstreamApp = HttpApp[IO] { req =>
        req.as[String].flatMap { body =>
          capturedBodyRef.set(body) *>
            Response[IO](Status.Created).withEntity(s"recebido: $body").pure[IO]
        }
      }

      mockClient = Client.fromHttpApp(mockUpstreamApp)

      incomingReq = Request[IO](Method.POST, uri"/api/v1/service/enviar")
        .withEntity(requestPayload)

      resp <- ReverseProxy.forward[IO](sampleRoute, incomingReq, mockClient)
      respBody <- resp.as[String]
      capturedUpstreamBody <- capturedBodyRef.get
    } yield {
      assertEquals(resp.status, Status.Created)
      assertEquals(capturedUpstreamBody, requestPayload)
      assertEquals(respBody, s"recebido: $requestPayload")
    }
  }

  test("ReverseProxy deve retornar 502 Bad Gateway em falha de conexão com o upstream") {
    val failingClient = Client[IO] { _ =>
      cats.effect.Resource.eval(IO.raiseError(new IOException("Connection refused by upstream")))
    }

    val incomingReq = Request[IO](Method.GET, uri"/api/v1/service/falha")

    ReverseProxy.forward[IO](sampleRoute, incomingReq, failingClient).map { resp =>
      assertEquals(resp.status, Status.BadGateway)
    }
  }

  test("ReverseProxy deve retornar 504 Gateway Timeout em estouro de tempo de resposta") {
    val timeoutClient = Client[IO] { _ =>
      cats.effect.Resource.eval(IO.raiseError(new TimeoutException("Upstream timed out")))
    }

    val incomingReq = Request[IO](Method.GET, uri"/api/v1/service/timeout")

    ReverseProxy.forward[IO](sampleRoute, incomingReq, timeoutClient).map { resp =>
      assertEquals(resp.status, Status.GatewayTimeout)
    }
  }

  test("ProxyRoutes deve retornar 405 MethodNotAllowed com cabeçalho Allow quando método não bater") {
    val dummyClient = Client.fromHttpApp(HttpApp[IO](_ => Response[IO](Status.Ok).pure[IO]))
    val deleteReq = Request[IO](Method.DELETE, uri"/api/v1/service/delete")

    for {
      cache <- com.example.apigateway.cache.RouteCache.of[IO](List(sampleRoute))
      httpRoutes = ProxyRoutes[IO](cache, dummyClient)
      maybeResp <- httpRoutes.run(deleteReq).value
    } yield {
      assert(maybeResp.isDefined)
      val resp = maybeResp.get
      assertEquals(resp.status, Status.MethodNotAllowed)
      assert(resp.headers.get(ci"Allow").isDefined)
    }
  }

  test("ProxyRoutes deve retornar None (404) quando o path não casar") {
    val dummyClient = Client.fromHttpApp(HttpApp[IO](_ => Response[IO](Status.Ok).pure[IO]))
    val notFoundReq = Request[IO](Method.GET, uri"/api/v1/outro-servico")

    for {
      cache <- com.example.apigateway.cache.RouteCache.of[IO](List(sampleRoute))
      httpRoutes = ProxyRoutes[IO](cache, dummyClient)
      maybeResp <- httpRoutes.run(notFoundReq).value
    } yield {
      assertEquals(maybeResp, None)
    }
  }
