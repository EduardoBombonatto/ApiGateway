package com.example.apigateway.discovery

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all._
import com.example.apigateway.config.EurekaConfig
import com.example.apigateway.discovery.model.ServiceInstance

import scala.concurrent.duration._

/** Gerenciador e cache concorrente de instâncias de microsserviços. */
trait ServiceRegistry[F[_]]:

  /** Retorna a lista de instâncias com status UP para um determinado serviço. */
  def getHealthyInstances(serviceName: String): F[Vector[ServiceInstance]]

  /** Retorna todo o mapa atual do catálogo em cache. */
  def getAllInstances: F[Map[String, Vector[ServiceInstance]]]

  /** Força a sincronização imediata do cache consultando o EurekaClient. */
  def refresh: F[Unit]

  /** Inicia a sincronização periódica e os heartbeats em segundo plano como um Resource. */
  def startBackgroundSync: Resource[F, Unit]

object ServiceRegistry:

  /** Construtor funcional que aloca o cache atômico Ref e conecta com o EurekaClient. */
  def apply[F[_]: Async](
      eurekaClient: EurekaClient[F],
      config: EurekaConfig
  ): F[ServiceRegistry[F]] =
    for {
      initialCache <- eurekaClient.fetchAllInstances
      cacheRef <- Ref.of[F, Map[String, Vector[ServiceInstance]]](initialCache)
    } yield new ServiceRegistry[F]:

      override def getHealthyInstances(serviceName: String): F[Vector[ServiceInstance]] =
        cacheRef.get.map(_.getOrElse(serviceName.toUpperCase, Vector.empty).filter(_.isHealthy))

      override def getAllInstances: F[Map[String, Vector[ServiceInstance]]] =
        cacheRef.get

      override def refresh: F[Unit] =
        for {
          fresh <- eurekaClient.fetchAllInstances
          _ <- cacheRef.set(fresh)
        } yield ()

      override def startBackgroundSync: Resource[F, Unit] =
        if !config.enabled then Resource.unit[F]
        else
          val ownInstance = ServiceInstance(
            serviceName = config.appName,
            instanceId = s"${config.instanceHost}:${config.appName}:${config.instancePort}",
            host = config.instanceHost,
            port = config.instancePort
          )

          val syncStream = fs2.Stream
            .awakeEvery[F](config.refreshIntervalSeconds.seconds)
            .evalMap(_ => refresh)
            .compile
            .drain

          val heartbeatStream =
            if config.registerWithEureka then
              fs2.Stream
                .awakeEvery[F](config.heartbeatIntervalSeconds.seconds)
                .evalMap(_ => eurekaClient.heartbeat(ownInstance.serviceName, ownInstance.instanceId))
                .compile
                .drain
            else
              Async[F].never[Unit]

          val registerLifecycle = Resource.make(
            for {
              _ <- if config.registerWithEureka then eurekaClient.register(ownInstance) else Async[F].unit
              _ <- refresh
            } yield ()
          )(_ =>
            if config.registerWithEureka then eurekaClient.unregister(ownInstance.serviceName, ownInstance.instanceId)
            else Async[F].unit
          )

          for {
            _ <- registerLifecycle
            _ <- Resource.make(Async[F].start(syncStream))(_.cancel)
            _ <- if config.registerWithEureka then Resource.make(Async[F].start(heartbeatStream))(_.cancel) else Resource.unit[F]
          } yield ()

  /** Construtor para testes a partir de instâncias estáticas em memória. */
  def of[F[_]: Async](staticInstances: Map[String, Vector[ServiceInstance]]): F[ServiceRegistry[F]] =
    Ref.of[F, Map[String, Vector[ServiceInstance]]](staticInstances).map { cacheRef =>
      new ServiceRegistry[F]:
        override def getHealthyInstances(serviceName: String): F[Vector[ServiceInstance]] =
          cacheRef.get.map(_.getOrElse(serviceName.toUpperCase, Vector.empty).filter(_.isHealthy))

        override def getAllInstances: F[Map[String, Vector[ServiceInstance]]] =
          cacheRef.get

        override def refresh: F[Unit] = Async[F].unit

        override def startBackgroundSync: Resource[F, Unit] = Resource.unit[F]
    }
