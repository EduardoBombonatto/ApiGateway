package com.example.apigateway.discovery

import cats.effect.IO
import com.example.apigateway.discovery.model.ServiceInstance
import munit.CatsEffectSuite

class LoadBalancerSpec extends CatsEffectSuite:

  val inst1: ServiceInstance = ServiceInstance("ORDER-SERVICE", "ord-1", "10.0.0.1", 8081, status = "UP")
  val inst2: ServiceInstance = ServiceInstance("ORDER-SERVICE", "ord-2", "10.0.0.2", 8082, status = "UP")
  val inst3: ServiceInstance = ServiceInstance("ORDER-SERVICE", "ord-3", "10.0.0.3", 8083, status = "UP")
  val downInst: ServiceInstance = ServiceInstance("ORDER-SERVICE", "ord-down", "10.0.0.4", 8084, status = "DOWN")

  val initialMap: Map[String, Vector[ServiceInstance]] = Map(
    "ORDER-SERVICE" -> Vector(inst1, inst2, inst3, downInst)
  )

  test("RoundRobinLoadBalancer deve alternar sequencialmente apenas entre instâncias UP") {
    for {
      lb <- LoadBalancer.of[IO](initialMap)

      c1 <- lb.choose("ORDER-SERVICE")
      c2 <- lb.choose("ORDER-SERVICE")
      c3 <- lb.choose("ORDER-SERVICE")
      c4 <- lb.choose("ORDER-SERVICE")
    } yield {
      // 1º salto -> inst1
      assertEquals(c1.map(_.instanceId), Some("ord-1"))
      // 2º salto -> inst2
      assertEquals(c2.map(_.instanceId), Some("ord-2"))
      // 3º salto -> inst3
      assertEquals(c3.map(_.instanceId), Some("ord-3"))
      // 4º salto -> ciclo reinicia em inst1 (ignorando inst-down)
      assertEquals(c4.map(_.instanceId), Some("ord-1"))
    }
  }

  test("LoadBalancer deve ser case-insensitive para o nome do serviço") {
    for {
      lb <- LoadBalancer.of[IO](initialMap)
      lowerChoice <- lb.choose("order-service")
    } yield {
      assert(lowerChoice.isDefined)
      assertEquals(lowerChoice.get.serviceName, "ORDER-SERVICE")
    }
  }

  test("LoadBalancer deve retornar None quando o serviço não possuir instâncias ativas") {
    for {
      lb <- LoadBalancer.of[IO](initialMap)
      emptyChoice <- lb.choose("UNKNOWN-SERVICE")
    } yield {
      assertEquals(emptyChoice, None)
    }
  }
