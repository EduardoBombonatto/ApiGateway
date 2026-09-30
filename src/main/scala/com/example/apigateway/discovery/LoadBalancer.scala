package com.example.apigateway.discovery

import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.example.apigateway.discovery.model.ServiceInstance

import scala.util.Random

/** Contrato do algoritmo de balanceamento de carga entre nós de um serviço. */
trait LoadBalancer[F[_]]:

  /** Escolhe uma instância saudável para atender à requisição. Retorna None se não houver instâncias UP. */
  def choose(serviceName: String): F[Option[ServiceInstance]]

object LoadBalancer:

  /** Cria um balanceador Round-Robin atômico (lock-free) apoiado no Ref do Cats Effect. */
  def roundRobin[F[_]: Async](registry: ServiceRegistry[F]): F[LoadBalancer[F]] =
    Ref.of[F, Map[String, Int]](Map.empty).map { counterRef =>
      new LoadBalancer[F]:
        override def choose(serviceName: String): F[Option[ServiceInstance]] =
          val key = serviceName.toUpperCase
          registry.getHealthyInstances(key).flatMap { instances =>
            if instances.isEmpty then
              Async[F].pure(None)
            else
              counterRef.modify { counters =>
                val currentIndex = counters.getOrElse(key, -1)
                // Ciclo seguro entre 0 e (size - 1)
                val nextIndex = (currentIndex + 1) % instances.size
                val updatedCounters = counters + (key -> nextIndex)
                val selected = instances(nextIndex)
                (updatedCounters, Some(selected))
              }
          }
    }

  /** Cria um balanceador aleatório (Random) simples. */
  def random[F[_]: Async](registry: ServiceRegistry[F]): LoadBalancer[F] =
    new LoadBalancer[F]:
      override def choose(serviceName: String): F[Option[ServiceInstance]] =
        registry.getHealthyInstances(serviceName.toUpperCase).map { instances =>
          if instances.isEmpty then None
          else
            val idx = Random.nextInt(instances.size)
            Some(instances(idx))
        }

  /** Cria um balanceador Round-Robin a partir de instâncias estáticas em memória (ideal para testes). */
  def of[F[_]: Async](staticInstances: Map[String, Vector[ServiceInstance]]): F[LoadBalancer[F]] =
    for {
      reg <- ServiceRegistry.of[F](staticInstances)
      lb <- roundRobin[F](reg)
    } yield lb
