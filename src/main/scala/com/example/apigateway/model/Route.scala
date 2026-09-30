package com.example.apigateway.model

import org.http4s.{Method, Uri}

/** Representação imutável de uma rota cadastrada no API Gateway. */
final case class Route(
    id: String,
    prefix: Uri.Path,
    methods: Set[Method] = Set.empty,
    targetUri: Uri,
    stripPrefix: Boolean = true,
    authRequired: Boolean = false,
    requiredRoles: Set[String] = Set.empty,
    rateLimitCapacity: Option[Int] = None,
    rateLimitRefillRate: Option[Int] = None,
    circuitBreakerEnabled: Boolean = false,
    fallbackUri: Option[Uri] = None
)
