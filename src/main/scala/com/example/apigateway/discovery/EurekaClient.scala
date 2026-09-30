package com.example.apigateway.discovery

import cats.effect.Async
import cats.syntax.all._
import com.example.apigateway.config.EurekaConfig
import com.example.apigateway.discovery.model.{EurekaRegistry, ServiceInstance}
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.typelevel.ci._

/** Contrato do cliente HTTP para comunicação com a API REST do Netflix Eureka. */
trait EurekaClient[F[_]]:

  /** Busca todas as instâncias saudáveis registradas no catálogo do Eureka. */
  def fetchAllInstances: F[Map[String, Vector[ServiceInstance]]]

  /** Busca as instâncias ativas de um serviço específico. */
  def fetchInstances(serviceName: String): F[Vector[ServiceInstance]]

  /** Registra uma instância no catálogo do Eureka. */
  def register(instance: ServiceInstance): F[Unit]

  /** Envia batimento cardíaco (heartbeat) para renovar a concessão no Eureka. */
  def heartbeat(appName: String, instanceId: String): F[Unit]

  /** Remove o registro da instância ao desligar o serviço. */
  def unregister(appName: String, instanceId: String): F[Unit]

object EurekaClient:

  /** Construtor funcional para o EurekaClient utilizando o EmberClient HTTP e Circe. */
  def apply[F[_]: Async](client: Client[F], config: EurekaConfig): EurekaClient[F] = new EurekaClient[F]:

    private val baseUri: Uri =
      val clean = if config.eurekaUrl.endsWith("/") then config.eurekaUrl.dropRight(1) else config.eurekaUrl
      Uri.unsafeFromString(clean)

    private val jsonHeader = Header.Raw(ci"Accept", "application/json")
    private val contentTypeJson = Header.Raw(ci"Content-Type", "application/json")

    override def fetchAllInstances: F[Map[String, Vector[ServiceInstance]]] =
      val target = baseUri / "apps"
      val req = Request[F](Method.GET, target).putHeaders(jsonHeader)

      client.expect[Json](req).map { json =>
        EurekaRegistry.applicationsDecoder.decodeJson(json) match
          case Right(allApps) =>
            // Filtra mantendo apenas instâncias com status UP
            allApps.map { case (name, instances) =>
              (name.toUpperCase, instances.filter(_.isHealthy))
            }
          case Left(_) =>
            Map.empty[String, Vector[ServiceInstance]]
      }.handleErrorWith { _ =>
        // Resiliência: se o Eureka estiver indisponível no momento, não quebra a aplicação
        Async[F].pure(Map.empty[String, Vector[ServiceInstance]])
      }

    override def fetchInstances(serviceName: String): F[Vector[ServiceInstance]] =
      fetchAllInstances.map(_.getOrElse(serviceName.toUpperCase, Vector.empty))

    override def register(instance: ServiceInstance): F[Unit] =
      val target = baseUri / "apps" / instance.serviceName.toUpperCase
      val payload = buildRegistrationPayload(instance)
      val req = Request[F](Method.POST, target)
        .putHeaders(contentTypeJson, jsonHeader)
        .withEntity(payload)

      client.status(req).void.handleErrorWith(_ => Async[F].unit)

    override def heartbeat(appName: String, instanceId: String): F[Unit] =
      val target = baseUri / "apps" / appName.toUpperCase / instanceId
      val req = Request[F](Method.PUT, target).putHeaders(jsonHeader)

      client.status(req).void.handleErrorWith(_ => Async[F].unit)

    override def unregister(appName: String, instanceId: String): F[Unit] =
      val target = baseUri / "apps" / appName.toUpperCase / instanceId
      val req = Request[F](Method.DELETE, target).putHeaders(jsonHeader)

      client.status(req).void.handleErrorWith(_ => Async[F].unit)

    private def buildRegistrationPayload(instance: ServiceInstance): Json =
      Json.obj(
        "instance" -> Json.obj(
          "instanceId" -> Json.fromString(instance.instanceId),
          "hostName" -> Json.fromString(instance.host),
          "app" -> Json.fromString(instance.serviceName.toUpperCase),
          "ipAddr" -> Json.fromString(instance.host),
          "status" -> Json.fromString("UP"),
          "port" -> Json.obj(
            "$" -> Json.fromInt(instance.port),
            "@enabled" -> Json.fromString("true")
          ),
          "securePort" -> Json.obj(
            "$" -> Json.fromInt(if instance.secure then 443 else 8443),
            "@enabled" -> Json.fromString(instance.secure.toString)
          ),
          "dataCenterInfo" -> Json.obj(
            "@class" -> Json.fromString("com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo"),
            "name" -> Json.fromString("MyOwn")
          )
        )
      )
