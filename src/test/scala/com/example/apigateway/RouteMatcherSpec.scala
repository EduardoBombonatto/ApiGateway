package com.example.apigateway

import cats.effect.IO
import com.example.apigateway.model.Route
import com.example.apigateway.routing.{MatchResult, RouteMatcher}
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.implicits._

class RouteMatcherSpec extends CatsEffectSuite:

  val usersRoute: Route = Route(
    id = "users",
    prefix = Uri.Path.unsafeFromString("/api/v1/users"),
    methods = Set(Method.GET, Method.POST),
    targetUri = uri"http://localhost:9001",
    stripPrefix = true
  )

  val userDetailsRoute: Route = Route(
    id = "user-details",
    prefix = Uri.Path.unsafeFromString("/api/v1/users/details"),
    methods = Set(Method.GET),
    targetUri = uri"http://localhost:9002",
    stripPrefix = true
  )

  val anyMethodRoute: Route = Route(
    id = "any-method",
    prefix = Uri.Path.unsafeFromString("/public"),
    methods = Set.empty, // aceita qualquer método
    targetUri = uri"http://localhost:9003",
    stripPrefix = false
  )

  val routes: List[Route] = List(usersRoute, userDetailsRoute, anyMethodRoute)

  test("Deve casar com sucesso uma rota por prefixo e método GET") {
    val req = Request[IO](Method.GET, uri"/api/v1/users/123")
    val result = RouteMatcher.findMatch(routes, req)

    assertEquals(result, MatchResult.Matched(usersRoute))
  }

  test("Deve priorizar a rota mais específica quando houver sobreposição de prefixos") {
    val req = Request[IO](Method.GET, uri"/api/v1/users/details/profile")
    val result = RouteMatcher.findMatch(routes, req)

    assertEquals(result, MatchResult.Matched(userDetailsRoute))
  }

  test("Deve retornar MethodNotAllowed quando o path casar mas o método HTTP não for aceito") {
    val req = Request[IO](Method.DELETE, uri"/api/v1/users/123")
    val result = RouteMatcher.findMatch(routes, req)

    assertEquals(result, MatchResult.MethodNotAllowed(Set(Method.GET, Method.POST)))
  }

  test("Deve aceitar qualquer método quando a rota tiver conjunto de métodos vazio") {
    val reqGet = Request[IO](Method.GET, uri"/public/info")
    val reqPost = Request[IO](Method.POST, uri"/public/info")
    val reqPut = Request[IO](Method.PUT, uri"/public/info")

    assertEquals(RouteMatcher.findMatch(routes, reqGet), MatchResult.Matched(anyMethodRoute))
    assertEquals(RouteMatcher.findMatch(routes, reqPost), MatchResult.Matched(anyMethodRoute))
    assertEquals(RouteMatcher.findMatch(routes, reqPut), MatchResult.Matched(anyMethodRoute))
  }

  test("Deve retornar NotFound quando o path não coincidir com nenhuma rota") {
    val req = Request[IO](Method.GET, uri"/api/v2/orders")
    val result = RouteMatcher.findMatch(routes, req)

    assertEquals(result, MatchResult.NotFound)
  }

  test("Não deve dar falso positivo em prefixos parciais (ex: /users-extra vs /users)") {
    val req = Request[IO](Method.GET, uri"/api/v1/users-extra")
    val result = RouteMatcher.findMatch(routes, req)

    assertEquals(result, MatchResult.NotFound)
  }
