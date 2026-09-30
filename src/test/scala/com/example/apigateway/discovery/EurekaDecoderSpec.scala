package com.example.apigateway.discovery

import com.example.apigateway.discovery.model.EurekaRegistry
import io.circe.parser._
import munit.FunSuite

class EurekaDecoderSpec extends FunSuite:

  test("EurekaRegistry decoder deve processar JSON padrão do Eureka com múltiplas aplicações") {
    val jsonString =
      """
      {
        "applications": {
          "application": [
            {
              "name": "ORDER-SERVICE",
              "instance": [
                {
                  "instanceId": "order-1",
                  "app": "ORDER-SERVICE",
                  "ipAddr": "192.168.1.10",
                  "status": "UP",
                  "port": { "$": 8081, "@enabled": "true" },
                  "securePort": { "$": 443, "@enabled": "false" }
                },
                {
                  "instanceId": "order-2",
                  "app": "ORDER-SERVICE",
                  "ipAddr": "192.168.1.11",
                  "status": "DOWN",
                  "port": { "$": 8082, "@enabled": "true" },
                  "securePort": { "$": 443, "@enabled": "false" }
                }
              ]
            },
            {
              "name": "PAYMENT-SERVICE",
              "instance": [
                {
                  "instanceId": "pay-1",
                  "app": "PAYMENT-SERVICE",
                  "ipAddr": "192.168.1.20",
                  "status": "UP",
                  "port": { "$": 9000, "@enabled": "true" },
                  "securePort": { "$": 443, "@enabled": "false" }
                }
              ]
            }
          ]
        }
      }
      """

    val parsedJson = parse(jsonString).getOrElse(fail("Falha ao analisar JSON"))
    val result = EurekaRegistry.applicationsDecoder.decodeJson(parsedJson)

    assert(result.isRight)
    val map = result.toOption.get

    assert(map.contains("ORDER-SERVICE"))
    assert(map.contains("PAYMENT-SERVICE"))

    val orderInstances = map("ORDER-SERVICE")
    assertEquals(orderInstances.size, 2)
    assertEquals(orderInstances(0).host, "192.168.1.10")
    assertEquals(orderInstances(0).port, 8081)
    assertEquals(orderInstances(0).status, "UP")

    val payInstances = map("PAYMENT-SERVICE")
    assertEquals(payInstances.size, 1)
    assertEquals(payInstances(0).port, 9000)
  }

  test("EurekaRegistry decoder deve processar JSON com aplicação única e instância única") {
    val jsonString =
      """
      {
        "applications": {
          "application": {
            "name": "AUTH-SERVICE",
            "instance": {
              "instanceId": "auth-1",
              "app": "AUTH-SERVICE",
              "ipAddr": "10.0.0.5",
              "status": "UP",
              "port": { "$": 7000, "@enabled": "true" }
            }
          }
        }
      }
      """

    val parsedJson = parse(jsonString).getOrElse(fail("Falha ao analisar JSON"))
    val result = EurekaRegistry.applicationsDecoder.decodeJson(parsedJson)

    assert(result.isRight)
    val map = result.toOption.get

    assert(map.contains("AUTH-SERVICE"))
    val instances = map("AUTH-SERVICE")
    assertEquals(instances.size, 1)
    assertEquals(instances.head.host, "10.0.0.5")
    assertEquals(instances.head.port, 7000)
  }
