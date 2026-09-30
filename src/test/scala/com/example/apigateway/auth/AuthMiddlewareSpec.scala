package com.example.apigateway.auth

import cats.effect.IO
import com.example.apigateway.model.Route
import munit.CatsEffectSuite
import org.http4s._
import org.typelevel.ci._

class AuthMiddlewareSpec extends CatsEffectSuite:

  val secret = "my-test-super-secret-key-that-is-long-enough"
  val authService: AuthService[IO] = AuthService[IO](secret, "test-issuer")
  val middleware: AuthMiddleware[IO] = AuthMiddleware[IO](authService)

  val publicRoute: Route = Route(
    id = "public-route",
    prefix = Uri.Path.unsafeFromString("/public"),
    targetUri = Uri.unsafeFromString("http://localhost:8081"),
    authRequired = false
  )

  val protectedRoute: Route = Route(
    id = "protected-route",
    prefix = Uri.Path.unsafeFromString("/api"),
    targetUri = Uri.unsafeFromString("http://localhost:8081"),
    authRequired = true,
    requiredRoles = Set("admin")
  )

  test("Deve permitir rota pública sem token e remover headers x-user-* externos") {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("/public/items"),
      headers = Headers(
        Header.Raw(ci"x-user-id", "attacker"),
        Header.Raw(ci"x-user-roles", "superadmin")
      )
    )

    middleware.authenticate(publicRoute, req).map {
      case Right(sanitizedReq) =>
        assertEquals(sanitizedReq.headers.get(ci"x-user-id"), None)
        assertEquals(sanitizedReq.headers.get(ci"x-user-roles"), None)
      case Left(_) =>
        fail("Rota pública deveria passar")
    }
  }

  test("Deve rejeitar rota autenticada sem cabeçalho Authorization com 401") {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("/api/secrets")
    )

    middleware.authenticate(protectedRoute, req).map {
      case Left(resp) =>
        assertEquals(resp.status, Status.Unauthorized)
      case Right(_) =>
        fail("Deveria ter sido rejeitado com 401")
    }
  }

  test("Deve rejeitar rota com token inválido com 401") {
    val req = Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString("/api/secrets"),
      headers = Headers(Header.Raw(ci"Authorization", "Bearer invalid-garbage-token"))
    )

    middleware.authenticate(protectedRoute, req).map {
      case Left(resp) =>
        assertEquals(resp.status, Status.Unauthorized)
      case Right(_) =>
        fail("Deveria ter sido rejeitado com 401")
    }
  }

  test("Deve rejeitar rota com roles insuficientes com 403") {
    for {
      token <- authService.generateToken("user-normal", Set("guest"))
      req = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString("/api/secrets"),
        headers = Headers(Header.Raw(ci"Authorization", s"Bearer $token"))
      )
      res <- middleware.authenticate(protectedRoute, req)
    } yield {
      res match
        case Left(resp) =>
          assertEquals(resp.status, Status.Forbidden)
        case Right(_) =>
          fail("Deveria ter retornado 403 Forbidden")
    }
  }

  test("Deve aprovar token com roles adequadas e injetar headers X-User-Id e X-User-Roles") {
    for {
      token <- authService.generateToken("user-adm", Set("admin", "editor"))
      req = Request[IO](
        method = Method.GET,
        uri = Uri.unsafeFromString("/api/secrets"),
        headers = Headers(
          Header.Raw(ci"Authorization", s"Bearer $token"),
          Header.Raw(ci"x-user-id", "attacker") // spoof attempt
        )
      )
      res <- middleware.authenticate(protectedRoute, req)
    } yield {
      res match
        case Right(enrichedReq) =>
          assertEquals(enrichedReq.headers.get(ci"x-user-id").map(_.head.value), Some("user-adm"))
          val rolesHeader = enrichedReq.headers.get(ci"x-user-roles").map(_.head.value).getOrElse("")
          assert(rolesHeader.contains("admin"))
          assert(rolesHeader.contains("editor"))
        case Left(resp) =>
          fail(s"Deveria ter aprovado, mas retornou status ${resp.status}")
    }
  }
