package com.example.apigateway.ratelimit

import cats.Applicative
import cats.syntax.all._
import com.example.apigateway.model.Route
import org.http4s.Request
import org.typelevel.ci._

/** Contrato funcional para resolução de chaves de rate limiting a partir da rota e requisição. */
trait KeyResolver[F[_]]:
  def resolveKey(route: Route, req: Request[F]): F[String]

object KeyResolver:

  def apply[F[_]: Applicative]: KeyResolver[F] = new KeyResolver[F]:
    override def resolveKey(route: Route, req: Request[F]): F[String] =
      // 1. Se houver usuário autenticado no header X-User-Id, limita por usuário
      val userOpt = req.headers.get(ci"x-user-id").map(_.head.value.trim).filter(_.nonEmpty)

      val key = userOpt match
        case Some(userId) =>
          s"ratelimit:user:$userId:${route.id}"
        case None =>
          // 2. Caso contrário, limita pelo IP de origem
          val clientIp = req.headers
            .get(ci"x-forwarded-for")
            .map(_.head.value.split(",").head.trim)
            .orElse(req.remoteAddr.map(_.toString))
            .orElse(req.from.map(_.toInetAddress.getHostAddress))
            .getOrElse("unknown")
          s"ratelimit:ip:$clientIp:${route.id}"

      key.pure[F]
