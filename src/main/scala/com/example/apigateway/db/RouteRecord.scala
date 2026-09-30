package com.example.apigateway.db

import com.example.apigateway.model.Route
import org.http4s.{Method, Uri}
import java.time.Instant

/** Representação persistível de uma rota no banco de dados. */
final case class RouteRecord(
    id: String,
    prefix: String,
    methods: List[String],
    targetUri: String,
    stripPrefix: Boolean = true,
    enabled: Boolean = true,
    authRequired: Boolean = false,
    requiredRoles: List[String] = Nil,
    rateLimitCapacity: Option[Int] = None,
    rateLimitRefillRate: Option[Int] = None,
    circuitBreakerEnabled: Boolean = false,
    fallbackUri: Option[String] = None,
    createdAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None
):
  /** Converte o registro de banco para a entidade de domínio imutável [[Route]]. */
  def toDomain: Either[String, Route] =
    Uri.fromString(targetUri).left.map(_.message).map { parsedUri =>
      Route(
        id = id,
        prefix = Uri.Path.unsafeFromString(prefix),
        methods = methods.flatMap(m => Method.fromString(m.trim.toUpperCase).toOption).toSet,
        targetUri = parsedUri,
        stripPrefix = stripPrefix,
        authRequired = authRequired,
        requiredRoles = requiredRoles.toSet,
        rateLimitCapacity = rateLimitCapacity,
        rateLimitRefillRate = rateLimitRefillRate,
        circuitBreakerEnabled = circuitBreakerEnabled,
        fallbackUri = fallbackUri.flatMap(u => Uri.fromString(u).toOption)
      )
    }

object RouteRecord:

  /** Converte uma rota de domínio para o registro de banco. */
  def fromDomain(route: Route, enabled: Boolean = true): RouteRecord =
    RouteRecord(
      id = route.id,
      prefix = route.prefix.renderString,
      methods = route.methods.map(_.name).toList,
      targetUri = route.targetUri.renderString,
      stripPrefix = route.stripPrefix,
      enabled = enabled,
      authRequired = route.authRequired,
      requiredRoles = route.requiredRoles.toList,
      rateLimitCapacity = route.rateLimitCapacity,
      rateLimitRefillRate = route.rateLimitRefillRate,
      circuitBreakerEnabled = route.circuitBreakerEnabled,
      fallbackUri = route.fallbackUri.map(_.renderString)
    )
