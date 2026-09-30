package com.example.apigateway.auth

final case class JwtClaims(
    userId: String,
    roles: Set[String] = Set.empty,
    rawClaims: Map[String, String] = Map.empty
)

sealed trait AuthError:
  def message: String

object AuthError:
  case object MissingToken extends AuthError:
    override val message: String = "Cabeçalho Authorization com token Bearer ausente."

  final case class InvalidToken(reason: String) extends AuthError:
    override def message: String = s"Token JWT inválido: $reason"

  case object ExpiredToken extends AuthError:
    override val message: String = "Token JWT expirado."

  final case class InsufficientRoles(required: Set[String], actual: Set[String]) extends AuthError:
    override def message: String =
      s"Acesso proibido. Roles necessárias: [${required.mkString(", ")}], roles do usuário: [${actual.mkString(", ")}]."
