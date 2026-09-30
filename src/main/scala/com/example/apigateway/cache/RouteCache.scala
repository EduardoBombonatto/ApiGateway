package com.example.apigateway.cache

import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.example.apigateway.db.RouteRepository
import com.example.apigateway.model.Route
import doobie.Transactor

trait RouteCache[F[_]]:

  /** Retorna a lista atualizada de rotas compiladas em memória (lock-free, O(1)). */
  def getRoutes: F[List[Route]]

  /** Reconsulta o repositório e substitui atomicamente o estado em memória. */
  def reload: F[Unit]

object RouteCache:

  /** Inicializa o cache pré-aquecido a partir da trait RouteRepository. */
  def apply[F[_]: Async](repository: RouteRepository[F]): F[RouteCache[F]] =
    for {
      initialRoutes <- repository.findAllActive
      ref <- Ref.of[F, List[Route]](initialRoutes)
    } yield new RouteCache[F]:
      override def getRoutes: F[List[Route]] = ref.get
      override def reload: F[Unit] =
        repository.findAllActive.flatMap(ref.set)

  /** Sobrecarga para inicializar diretamente a partir do Transactor. */
  def apply[F[_]: Async](xa: Transactor[F]): F[RouteCache[F]] =
    apply(RouteRepository[F](xa))

  /** Construtor auxiliar para testes e rotas puramente em memória. */
  def of[F[_]: Async](initialRoutes: List[Route]): F[RouteCache[F]] =
    Ref.of[F, List[Route]](initialRoutes).map { ref =>
      new RouteCache[F]:
        override def getRoutes: F[List[Route]] = ref.get
        override def reload: F[Unit] = Async[F].unit
    }
