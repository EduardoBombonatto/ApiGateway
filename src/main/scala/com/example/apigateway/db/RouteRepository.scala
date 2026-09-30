package com.example.apigateway.db

import cats.effect.Async
import cats.syntax.all._
import com.example.apigateway.model.Route
import doobie._
import doobie.implicits._
import doobie.implicits.javatime._
import doobie.postgres.implicits._

/** Contrato abstrato para operações de persistência de rotas. */
trait RouteRepository[F[_]]:

  /** Recupera todas as rotas ativas (enabled = true) convertidas para o domínio. */
  def findAllActive: F[List[Route]]

  /** Retorna todas as rotas (ativas e inativas). */
  def findAll: F[List[RouteRecord]]

  /** Busca uma rota pelo ID. */
  def findById(id: String): F[Option[RouteRecord]]

  /** Insere uma nova rota na tabela. */
  def insert(record: RouteRecord): F[RouteRecord]

  /** Atualiza uma rota existente pelo ID. */
  def update(id: String, record: RouteRecord): F[Option[RouteRecord]]

  /** Remove uma rota pelo ID. */
  def delete(id: String): F[Boolean]

object RouteRepository:

  private val columns = fr"id, prefix, methods, target_uri, strip_prefix, enabled, auth_required, required_roles, rate_limit_capacity, rate_limit_refill_rate, circuit_breaker_enabled, fallback_uri, created_at, updated_at"

  /** Construtor funcional que cria a implementação da trait RouteRepository vinculada a um Transactor. */
  def apply[F[_]: Async](xa: Transactor[F]): RouteRepository[F] = new RouteRepository[F]:

    override def findAllActive: F[List[Route]] =
      (fr"SELECT " ++ columns ++ fr"FROM routes WHERE enabled = TRUE ORDER BY prefix")
        .query[RouteRecord]
        .to[List]
        .transact(xa)
        .map(_.flatMap(_.toDomain.toOption))

    override def findAll: F[List[RouteRecord]] =
      (fr"SELECT " ++ columns ++ fr"FROM routes ORDER BY created_at DESC")
        .query[RouteRecord]
        .to[List]
        .transact(xa)

    override def findById(id: String): F[Option[RouteRecord]] =
      (fr"SELECT " ++ columns ++ fr"FROM routes WHERE id = $id")
        .query[RouteRecord]
        .option
        .transact(xa)

    override def insert(record: RouteRecord): F[RouteRecord] =
      sql"""
        INSERT INTO routes (
          id, prefix, methods, target_uri, strip_prefix, enabled,
          auth_required, required_roles, rate_limit_capacity, rate_limit_refill_rate,
          circuit_breaker_enabled, fallback_uri, created_at, updated_at
        ) VALUES (
          ${record.id}, ${record.prefix}, ${record.methods}, ${record.targetUri}, ${record.stripPrefix}, ${record.enabled},
          ${record.authRequired}, ${record.requiredRoles}, ${record.rateLimitCapacity}, ${record.rateLimitRefillRate},
          ${record.circuitBreakerEnabled}, ${record.fallbackUri}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
      """
        .update
        .run
        .transact(xa)
        .as(record)

    override def update(id: String, record: RouteRecord): F[Option[RouteRecord]] =
      sql"""
        UPDATE routes
        SET prefix = ${record.prefix},
            methods = ${record.methods},
            target_uri = ${record.targetUri},
            strip_prefix = ${record.stripPrefix},
            enabled = ${record.enabled},
            auth_required = ${record.authRequired},
            required_roles = ${record.requiredRoles},
            rate_limit_capacity = ${record.rateLimitCapacity},
            rate_limit_refill_rate = ${record.rateLimitRefillRate},
            circuit_breaker_enabled = ${record.circuitBreakerEnabled},
            fallback_uri = ${record.fallbackUri},
            updated_at = CURRENT_TIMESTAMP
        WHERE id = $id
      """
        .update
        .run
        .transact(xa)
        .flatMap { rowsAffected =>
          if rowsAffected > 0 then findById(id)
          else Async[F].pure(None)
        }

    override def delete(id: String): F[Boolean] =
      sql"""
        DELETE FROM routes WHERE id = $id
      """
        .update
        .run
        .transact(xa)
        .map(_ > 0)
