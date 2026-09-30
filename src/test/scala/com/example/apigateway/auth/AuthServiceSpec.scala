package com.example.apigateway.auth

import cats.effect.IO
import munit.CatsEffectSuite

class AuthServiceSpec extends CatsEffectSuite:

  val secret = "my-test-super-secret-key-that-is-long-enough"
  val authService: AuthService[IO] = AuthService[IO](secret, "test-issuer")

  test("Deve gerar token JWT e validar com sucesso extraindo userId e roles") {
    for {
      token <- authService.generateToken("user-123", Set("admin", "operator"), ttlSeconds = 3600)
      result <- authService.validateToken(token)
    } yield {
      result match
        case Right(claims) =>
          assertEquals(claims.userId, "user-123")
          assertEquals(claims.roles, Set("admin", "operator"))
        case Left(err) =>
          fail(s"Esperava sucesso mas obteve erro: ${err.message}")
    }
  }

  test("Deve rejeitar token expirado com AuthError.ExpiredToken") {
    for {
      token <- authService.generateToken("user-expired", Set("user"), ttlSeconds = -10)
      result <- authService.validateToken(token)
    } yield {
      assertEquals(result, Left(AuthError.ExpiredToken))
    }
  }

  test("Deve rejeitar token com assinatura inválida com AuthError.InvalidToken") {
    val otherAuth = AuthService[IO]("other-different-secret-key-1234567890", "test-issuer")
    for {
      token <- otherAuth.generateToken("hacker", Set("admin"), ttlSeconds = 3600)
      result <- authService.validateToken(token)
    } yield {
      assert(result.isLeft)
      result.left.foreach { err =>
        assert(err.isInstanceOf[AuthError.InvalidToken])
      }
    }
  }

  test("Deve rejeitar token com emissor divergente") {
    val otherIssuerAuth = AuthService[IO](secret, "other-issuer")
    for {
      token <- otherIssuerAuth.generateToken("user-1", Set("user"), ttlSeconds = 3600)
      result <- authService.validateToken(token)
    } yield {
      assert(result.isLeft)
    }
  }
