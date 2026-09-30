package com.example.apigateway.auth

import cats.effect.Sync
import cats.syntax.all._
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.{JWTVerificationException, TokenExpiredException}

import java.time.Instant
import java.util.Date
import scala.jdk.CollectionConverters._

/** Contrato funcional para serviço de autenticação e validação de tokens JWT. */
trait AuthService[F[_]]:
  def validateToken(token: String): F[Either[AuthError, JwtClaims]]
  def generateToken(userId: String, roles: Set[String] = Set.empty, ttlSeconds: Long = 3600): F[String]

object AuthService:

  /** Cria uma instância de AuthService baseada em HMAC-SHA256 com chave secreta e emissor configurados. */
  def apply[F[_]: Sync](secret: String, issuer: String = "apigateway"): AuthService[F] =
    val algorithm = Algorithm.HMAC256(secret)
    val verifier = JWT.require(algorithm).withIssuer(issuer).build()

    new AuthService[F]:
      override def validateToken(token: String): F[Either[AuthError, JwtClaims]] =
        Sync[F].delay {
          try {
            val decoded = verifier.verify(token)
            val sub = Option(decoded.getSubject).filter(_.nonEmpty)
            val claimUserId = Option(decoded.getClaim("userId")).flatMap(c => Option(c.asString)).filter(_.nonEmpty)
            val finalUserId = sub.orElse(claimUserId).getOrElse("anonymous")

            val roles: Set[String] = Option(decoded.getClaim("roles")) match
              case Some(claim) if !claim.isMissing && !claim.isNull =>
                try {
                  claim.asList(classOf[String]).asScala.toSet
                } catch {
                  case _: Exception =>
                    Option(claim.asString).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)
                }
              case _ => Set.empty

            Right(JwtClaims(userId = finalUserId, roles = roles))
          } catch {
            case _: TokenExpiredException =>
              Left(AuthError.ExpiredToken)
            case e: JWTVerificationException =>
              Left(AuthError.InvalidToken(e.getMessage))
            case e: Throwable =>
              Left(AuthError.InvalidToken(e.getMessage))
          }
        }

      override def generateToken(userId: String, roles: Set[String] = Set.empty, ttlSeconds: Long = 3600): F[String] =
        Sync[F].delay {
          val now = Instant.now()
          val expiresAt = now.plusSeconds(ttlSeconds)
          JWT.create()
            .withIssuer(issuer)
            .withSubject(userId)
            .withClaim("userId", userId)
            .withClaim("roles", roles.toList.asJava)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        }
