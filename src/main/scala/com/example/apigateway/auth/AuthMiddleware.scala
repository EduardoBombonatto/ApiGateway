package com.example.apigateway.auth

import cats.Monad
import cats.syntax.all._
import com.example.apigateway.model.Route
import org.http4s._
import org.typelevel.ci._

/** Contrato funcional para interceptação e validação de autenticação/autorização na borda. */
trait AuthMiddleware[F[_]]:
  /** Valida autenticação e autorização para a rota e enriquece a requisição com X-User-Id e X-User-Roles confiáveis.
    * Retorna Left(Response) se falhar (401 ou 403), ou Right(Request) se aprovado.
    */
  def authenticate(route: Route, req: Request[F]): F[Either[Response[F], Request[F]]]

object AuthMiddleware:

  private val UntrustedUserHeaders: Set[CIString] = Set(
    ci"x-user-id",
    ci"x-user-roles"
  )

  def apply[F[_]: Monad](authService: AuthService[F]): AuthMiddleware[F] = new AuthMiddleware[F]:
    override def authenticate(route: Route, req: Request[F]): F[Either[Response[F], Request[F]]] =
      // 1. Sanitiza headers de usuário vindos de fora para evitar spoofing
      val sanitizedHeaders = req.headers.headers.filterNot(h => UntrustedUserHeaders.contains(h.name))
      val sanitizedReq = req.withHeaders(Headers(sanitizedHeaders))

      if !route.authRequired then
        (Right(sanitizedReq): Either[Response[F], Request[F]]).pure[F]
      else
        extractBearerToken(req) match
          case None =>
            val resp = Response[F](Status.Unauthorized)
              .withEntity(s"""{"error":"Unauthorized","message":"${AuthError.MissingToken.message}"}""")
              .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
            (Left(resp): Either[Response[F], Request[F]]).pure[F]

          case Some(token) =>
            authService.validateToken(token).map {
              case Left(AuthError.ExpiredToken) =>
                val resp = Response[F](Status.Unauthorized)
                  .withEntity(s"""{"error":"Unauthorized","message":"${AuthError.ExpiredToken.message}"}""")
                  .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
                Left(resp)

              case Left(AuthError.InvalidToken(reason)) =>
                val resp = Response[F](Status.Unauthorized)
                  .withEntity(s"""{"error":"Unauthorized","message":"${AuthError.InvalidToken(reason).message}"}""")
                  .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
                Left(resp)

              case Left(other) =>
                val resp = Response[F](Status.Unauthorized)
                  .withEntity(s"""{"error":"Unauthorized","message":"${other.message}"}""")
                  .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
                Left(resp)

              case Right(claims) =>
                // 2. Verifica autorização por roles (RBAC)
                if route.requiredRoles.nonEmpty && !route.requiredRoles.subsetOf(claims.roles) then
                  val resp = Response[F](Status.Forbidden)
                    .withEntity(
                      s"""{"error":"Forbidden","message":"${AuthError.InsufficientRoles(route.requiredRoles, claims.roles).message}"}"""
                    )
                    .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
                  Left(resp)
                else
                  // 3. Injeta headers confiáveis
                  val enrichedHeaders = Headers(
                    sanitizedHeaders ++ List(
                      Header.Raw(ci"x-user-id", claims.userId),
                      Header.Raw(ci"x-user-roles", claims.roles.mkString(","))
                    )
                  )
                  Right(sanitizedReq.withHeaders(enrichedHeaders))
            }

  private def extractBearerToken[F[_]](req: Request[F]): Option[String] =
    req.headers.get(ci"authorization").flatMap { h =>
      val raw = h.head.value.trim
      if raw.regionMatches(true, 0, "Bearer ", 0, 7) then
        Some(raw.substring(7).trim).filter(_.nonEmpty)
      else None
    }
