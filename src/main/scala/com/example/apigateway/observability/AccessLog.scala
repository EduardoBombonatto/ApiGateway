package com.example.apigateway.observability

import org.slf4j.LoggerFactory
import java.time.Instant

object AccessLog:
  private val logger = LoggerFactory.getLogger("com.example.apigateway.access")

  def log(
      correlationId: String,
      clientIp: String,
      method: String,
      path: String,
      routeId: String,
      status: Int,
      durationMs: Long,
      upstream: String
  ): Unit =
    val json =
      s"""{"timestamp":"${Instant.now()}","correlationId":"$correlationId","clientIp":"$clientIp","method":"$method","path":"$path","routeId":"$routeId","status":$status,"durationMs":$durationMs,"upstream":"$upstream"}"""
    logger.info(json)
