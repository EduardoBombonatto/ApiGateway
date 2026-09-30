package com.example.apigateway.admin

import com.example.apigateway.db.RouteRecord
import io.circe.Codec
import io.circe.generic.semiauto._

final case class CreateRouteRequest(
    id: Option[String] = None,
    prefix: String,
    methods: List[String] = Nil,
    targetUri: String,
    stripPrefix: Boolean = true,
    enabled: Boolean = true,
    authRequired: Boolean = false,
    requiredRoles: List[String] = Nil,
    rateLimitCapacity: Option[Int] = None,
    rateLimitRefillRate: Option[Int] = None,
    circuitBreakerEnabled: Boolean = false,
    fallbackUri: Option[String] = None
)

object CreateRouteRequest:
  given Codec[CreateRouteRequest] = deriveCodec[CreateRouteRequest]

final case class UpdateRouteRequest(
    prefix: String,
    methods: List[String] = Nil,
    targetUri: String,
    stripPrefix: Boolean = true,
    enabled: Boolean = true,
    authRequired: Boolean = false,
    requiredRoles: List[String] = Nil,
    rateLimitCapacity: Option[Int] = None,
    rateLimitRefillRate: Option[Int] = None,
    circuitBreakerEnabled: Boolean = false,
    fallbackUri: Option[String] = None
)

object UpdateRouteRequest:
  given Codec[UpdateRouteRequest] = deriveCodec[UpdateRouteRequest]

final case class RouteDto(
    id: String,
    prefix: String,
    methods: List[String],
    targetUri: String,
    stripPrefix: Boolean,
    enabled: Boolean,
    authRequired: Boolean = false,
    requiredRoles: List[String] = Nil,
    rateLimitCapacity: Option[Int] = None,
    rateLimitRefillRate: Option[Int] = None,
    circuitBreakerEnabled: Boolean = false,
    fallbackUri: Option[String] = None,
    createdAt: Option[String] = None,
    updatedAt: Option[String] = None
)

object RouteDto:
  given Codec[RouteDto] = deriveCodec[RouteDto]

  def fromRecord(record: RouteRecord): RouteDto =
    RouteDto(
      id = record.id,
      prefix = record.prefix,
      methods = record.methods,
      targetUri = record.targetUri,
      stripPrefix = record.stripPrefix,
      enabled = record.enabled,
      authRequired = record.authRequired,
      requiredRoles = record.requiredRoles,
      rateLimitCapacity = record.rateLimitCapacity,
      rateLimitRefillRate = record.rateLimitRefillRate,
      circuitBreakerEnabled = record.circuitBreakerEnabled,
      fallbackUri = record.fallbackUri,
      createdAt = record.createdAt.map(_.toString),
      updatedAt = record.updatedAt.map(_.toString)
    )
