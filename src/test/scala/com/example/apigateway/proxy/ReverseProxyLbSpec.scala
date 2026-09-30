package com.example.apigateway.proxy

import cats.effect.{IO, Ref}
import cats.syntax.all._
import com.example.apigateway.discovery.LoadBalancer
import com.example.apigateway.discovery.model.ServiceInstance
import com.example.apigateway.model.Route
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._

class ReverseProxyLbSpec extends CatsEffectSuite:

  val inst1: ServiceInstance = ServiceInstance("ORDER-SERVICE", "ord-1", "10.0.0.1", 8081, status = "UP")
  val inst2: ServiceInstance = ServiceInstance("ORDER-SERVICE", "ord-2", "10.0.0.2", 8082, status = "UP")

  val staticMap: Map[String, Vector[ServiceInstance]] = Map(
    "ORDER-SERVICE" -> Vector(inst1, inst2)
  )

  val lbRoute: Route = Route(
    id = "order-lb-route",
    prefix = Uri.Path.unsafeFromString("/api/v1/orders"),
    methods = Set(Method.GET),
    targetUri = uri"lb://ORDER-SERVICE/base",
    stripPrefix = true
  )

  test("ReverseProxy deve resolver esquema lb:// via LoadBalancer e alternar Round-Robin") {
    for {
      lb <- LoadBalancer.of[IO](staticMap)
      dispatchedUrisRef <- Ref.of[IO, List[Uri]](Nil)

      mockClient = Client.fromHttpApp(HttpApp[IO] { req =>
        dispatchedUrisRef.update(_ :+ req.uri) *>
          Response[IO](Status.Ok).withEntity("ok").pure[IO]
      })

      proxy = ReverseProxy[IO](mockClient, lb)

      req1 = Request[IO](Method.GET, uri"/api/v1/orders/100")
      req2 = Request[IO](Method.GET, uri"/api/v1/orders/200")

      resp1 <- proxy.forward(lbRoute, req1)
      resp2 <- proxy.forward(lbRoute, req2)

      uris <- dispatchedUrisRef.get
    } yield {
      assertEquals(resp1.status, Status.Ok)
      assertEquals(resp2.status, Status.Ok)

      assertEquals(uris.size, 2)
      // 1ª chamada balanceada para 10.0.0.1:8081
      assertEquals(uris(0).renderString, "http://10.0.0.1:8081/base/100")
      // 2ª chamada balanceada para 10.0.0.2:8082
      assertEquals(uris(1).renderString, "http://10.0.0.2:8082/base/200")
    }
  }

  test("ReverseProxy deve retornar 503 Service Unavailable quando o serviço lb:// não tiver instâncias UP") {
    for {
      lb <- LoadBalancer.of[IO](staticMap)
      dummyClient = Client.fromHttpApp(HttpApp[IO](_ => Response[IO](Status.Ok).pure[IO]))

      proxy = ReverseProxy[IO](dummyClient, lb)

      unregisteredRoute = Route(
        id = "unknown-lb-route",
        prefix = Uri.Path.unsafeFromString("/api/v1/unknown"),
        methods = Set(Method.GET),
        targetUri = uri"lb://SERVICO-INEXISTENTE",
        stripPrefix = true
      )

      req = Request[IO](Method.GET, uri"/api/v1/unknown/teste")
      resp <- proxy.forward(unregisteredRoute, req)
      respBody <- resp.as[String]
    } yield {
      assertEquals(resp.status, Status.ServiceUnavailable)
      assert(respBody.contains("Nenhuma instância saudável encontrada no Eureka"))
    }
  }

  test("ReverseProxy deve manter o encaminhamento direto para rotas HTTP normais sem lb://") {
    for {
      lb <- LoadBalancer.of[IO](staticMap)
      dispatchedUriRef <- Ref.of[IO, Option[Uri]](None)

      mockClient = Client.fromHttpApp(HttpApp[IO] { req =>
        dispatchedUriRef.set(Some(req.uri)) *>
          Response[IO](Status.Ok).withEntity("direct-ok").pure[IO]
      })

      proxy = ReverseProxy[IO](mockClient, lb)

      directRoute = Route(
        id = "direct-route",
        prefix = Uri.Path.unsafeFromString("/api/v1/direct"),
        methods = Set(Method.GET),
        targetUri = uri"http://servico-fixo:9000/app",
        stripPrefix = true
      )

      req = Request[IO](Method.GET, uri"/api/v1/direct/dados")
      resp <- proxy.forward(directRoute, req)
      maybeUri <- dispatchedUriRef.get
    } yield {
      assertEquals(resp.status, Status.Ok)
      assert(maybeUri.isDefined)
      assertEquals(maybeUri.get.renderString, "http://servico-fixo:9000/app/dados")
    }
  }
