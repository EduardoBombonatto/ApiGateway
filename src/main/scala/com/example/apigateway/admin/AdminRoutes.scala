package com.example.apigateway.admin

import cats.effect.Async
import cats.syntax.all._
import com.example.apigateway.cache.RouteCache
import com.example.apigateway.config.AdminConfig
import com.example.apigateway.db.{RouteRecord, RouteRepository}
import doobie.Transactor
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci._

import java.util.UUID

object AdminRoutes:

  /** Constrói os endpoints administrativos utilizando a trait RouteRepository. */
  def apply[F[_]: Async](
      repository: RouteRepository[F],
      routeCache: RouteCache[F],
      adminConfig: AdminConfig
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl._

    val adminKeyHeader = ci"X-Admin-Key"

    def isAuthenticated(req: Request[F]): Boolean =
      req.headers.get(adminKeyHeader).exists(_.head.value == adminConfig.apiKey)

    HttpRoutes.of[F] {
      case req if !isAuthenticated(req) =>
        Response[F](Status.Unauthorized)
          .withEntity(
            """{"error":"Unauthorized","message":"Chave administrativa inválida ou ausente no cabeçalho X-Admin-Key."}"""
          )
          .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
          .pure[F]

      case GET -> Root / "routes" =>
        for {
          records <- repository.findAll
          dtos = records.map(RouteDto.fromRecord)
          resp <- Ok(dtos)
        } yield resp

      case POST -> Root / "routes" / "reload" =>
        for {
          _ <- routeCache.reload
          routes <- routeCache.getRoutes
          resp <- Ok(s"""{"status":"reloaded","activeRoutes":${routes.size}}""")
        } yield resp

      case GET -> Root / "routes" / id =>
        for {
          maybeRecord <- repository.findById(id)
          resp <- maybeRecord match
            case Some(r) => Ok(RouteDto.fromRecord(r))
            case None =>
              NotFound(s"""{"error":"Not Found","message":"Rota '$id' não encontrada."}""")
                .map(_.putHeaders(Header.Raw(ci"Content-Type", "application/json")))
        } yield resp

      case req @ POST -> Root / "routes" =>
        for {
          body <- req.as[CreateRouteRequest]
          routeId = body.id.getOrElse(UUID.randomUUID().toString.take(8))
          record = RouteRecord(
            id = routeId,
            prefix = body.prefix,
            methods = body.methods,
            targetUri = body.targetUri,
            stripPrefix = body.stripPrefix,
            enabled = body.enabled,
            authRequired = body.authRequired,
            requiredRoles = body.requiredRoles,
            rateLimitCapacity = body.rateLimitCapacity,
            rateLimitRefillRate = body.rateLimitRefillRate,
            circuitBreakerEnabled = body.circuitBreakerEnabled,
            fallbackUri = body.fallbackUri
          )
          saved <- repository.insert(record)
          _ <- routeCache.reload
          resp <- Created(RouteDto.fromRecord(saved))
        } yield resp

      case req @ PUT -> Root / "routes" / id =>
        for {
          body <- req.as[UpdateRouteRequest]
          record = RouteRecord(
            id = id,
            prefix = body.prefix,
            methods = body.methods,
            targetUri = body.targetUri,
            stripPrefix = body.stripPrefix,
            enabled = body.enabled,
            authRequired = body.authRequired,
            requiredRoles = body.requiredRoles,
            rateLimitCapacity = body.rateLimitCapacity,
            rateLimitRefillRate = body.rateLimitRefillRate,
            circuitBreakerEnabled = body.circuitBreakerEnabled,
            fallbackUri = body.fallbackUri
          )
          updatedOpt <- repository.update(id, record)
          resp <- updatedOpt match
            case Some(updated) =>
              routeCache.reload *> Ok(RouteDto.fromRecord(updated))
            case None =>
              NotFound(s"""{"error":"Not Found","message":"Rota '$id' não encontrada."}""")
                .map(_.putHeaders(Header.Raw(ci"Content-Type", "application/json")))
        } yield resp

      case DELETE -> Root / "routes" / id =>
        for {
          deleted <- repository.delete(id)
          resp <- if deleted then
            routeCache.reload *> NoContent()
          else
            NotFound(s"""{"error":"Not Found","message":"Rota '$id' não encontrada."}""")
              .map(_.putHeaders(Header.Raw(ci"Content-Type", "application/json")))
        } yield resp
    }

  /** Sobrecarga para conveniência que recebe Transactor e instancia RouteRepository internamente. */
  def apply[F[_]: Async](
      xa: Transactor[F],
      routeCache: RouteCache[F],
      adminConfig: AdminConfig
  ): HttpRoutes[F] =
    apply(RouteRepository[F](xa), routeCache, adminConfig)
