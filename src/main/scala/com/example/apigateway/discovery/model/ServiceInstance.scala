package com.example.apigateway.discovery.model

import io.circe.{Decoder, HCursor}
import org.http4s.Uri

/** Representa uma instância física de um microsserviço registrada no Service Discovery. */
final case class ServiceInstance(
    serviceName: String,
    instanceId: String,
    host: String,
    port: Int,
    secure: Boolean = false,
    status: String = "UP"
):
  /** Retorna verdadeiro se a instância estiver no status UP. */
  def isHealthy: Boolean = status.equalsIgnoreCase("UP")

  /** Constrói a URI base para despacho HTTP. */
  def baseUri: Uri =
    val scheme = if secure then "https" else "http"
    Uri.unsafeFromString(s"$scheme://$host:$port")

object ServiceInstance:

  /** Decodificador Circe para instâncias individuais do Eureka.
    * Lida com a estrutura padrão do Eureka:
    * {
    *   "instanceId": "service-1",
    *   "app": "PEDIDOS-SERVICE",
    *   "ipAddr": "127.0.0.1",
    *   "status": "UP",
    *   "port": { "$": 8081, "@enabled": "true" }
    * }
    */
  given Decoder[ServiceInstance] = (c: HCursor) =>
    for {
      instanceId <- c.downField("instanceId").as[String]
      appName <- c.downField("app").as[String]
      host <- c.downField("ipAddr").as[String].orElse(c.downField("hostName").as[String])
      status <- c.downField("status").as[String].orElse(Right("UP"))
      port <- c.downField("port").downField("$").as[Int].orElse(c.downField("port").as[Int]).orElse(Right(8080))
      secureEnabled <- c.downField("securePort").downField("@enabled").as[String].map(_.equalsIgnoreCase("true")).orElse(Right(false))
    } yield ServiceInstance(
      serviceName = appName.toUpperCase,
      instanceId = instanceId,
      host = host,
      port = port,
      secure = secureEnabled,
      status = status
    )

/** Modelos auxiliares para decodificação do catálogo do Eureka: /eureka/apps */
object EurekaRegistry:

  given applicationsDecoder: Decoder[Map[String, Vector[ServiceInstance]]] = (c: HCursor) =>
    val appsCursor = c.downField("applications").downField("application")

    val decoded = appsCursor.as[List[io.circe.Json]] match
      case Right(appJsons) =>
        appJsons.flatMap(j => decodeSingleApp(j.hcursor)).toMap
      case Left(_) =>
        decodeSingleApp(appsCursor).map(s => Map(s)).getOrElse(Map.empty)

    Right(decoded)

  private def decodeSingleApp(appCursor: io.circe.ACursor): Option[(String, Vector[ServiceInstance])] =
    for {
      name <- appCursor.downField("name").as[String].toOption
      instances = decodeInstances(appCursor.downField("instance"))
    } yield (name.toUpperCase, instances)

  private def decodeInstances(instanceCursor: io.circe.ACursor): Vector[ServiceInstance] =
    // Eureka retorna array de instâncias ou um único objeto se houver só 1 instância
    instanceCursor.as[List[ServiceInstance]].toOption match
      case Some(list) => list.toVector
      case None =>
        instanceCursor.as[ServiceInstance].toOption match
          case Some(single) => Vector(single)
          case None         => Vector.empty
