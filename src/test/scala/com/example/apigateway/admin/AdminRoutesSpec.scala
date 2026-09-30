package com.example.apigateway.admin

import cats.effect.{IO, Resource}
import com.example.apigateway.cache.RouteCache
import com.example.apigateway.config.{AdminConfig, DbConfig}
import com.example.apigateway.db.{DatabaseMigrator, RouteRepository}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.typelevel.ci._

class AdminRoutesSpec extends CatsEffectSuite:

  val adminConfig: AdminConfig = AdminConfig(apiKey = "test-secret-key")
  val authHeader: Header.Raw = Header.Raw(ci"X-Admin-Key", "test-secret-key")

  val dbConfig: DbConfig = DbConfig(
    host = "localhost",
    port = 5432,
    database = "apigateway",
    user = "postgres",
    password = "postgres",
    poolSize = 2
  )

  val transactorResource: Resource[IO, HikariTransactor[IO]] =
    for {
      ec <- ExecutionContexts.fixedThreadPool[IO](2)
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        dbConfig.url,
        dbConfig.user,
        dbConfig.password,
        ec
      )
    } yield xa

  test("AdminRoutes deve barrar requisições sem o cabeçalho X-Admin-Key com 401") {
    for {
      _ <- DatabaseMigrator.migrate[IO](dbConfig)
      _ <- transactorResource.use { xa =>
        val repo = RouteRepository[IO](xa)
        for {
          cache <- RouteCache[IO](repo)
          routes = AdminRoutes[IO](repo, cache, adminConfig)

          req = Request[IO](Method.GET, uri"/routes")
          resp <- routes.run(req).value
        } yield {
          assert(resp.isDefined)
          assertEquals(resp.get.status, Status.Unauthorized)
        }
      }
    } yield ()
  }

  test("AdminRoutes deve barrar requisições com chave incorreta com 401") {
    for {
      _ <- DatabaseMigrator.migrate[IO](dbConfig)
      _ <- transactorResource.use { xa =>
        val repo = RouteRepository[IO](xa)
        for {
          cache <- RouteCache[IO](repo)
          routes = AdminRoutes[IO](repo, cache, adminConfig)

          req = Request[IO](Method.GET, uri"/routes")
            .putHeaders(Header.Raw(ci"X-Admin-Key", "chave-errada"))
          resp <- routes.run(req).value
        } yield {
          assert(resp.isDefined)
          assertEquals(resp.get.status, Status.Unauthorized)
        }
      }
    } yield ()
  }

  test("AdminRoutes deve permitir ciclo de vida completo (POST, GET, PUT, DELETE, RELOAD)") {
    for {
      _ <- DatabaseMigrator.migrate[IO](dbConfig)
      _ <- transactorResource.use { xa =>
        val repo = RouteRepository[IO](xa)
        for {
          _ <- repo.delete("test-route")
          cache <- RouteCache[IO](repo)
          routes = AdminRoutes[IO](repo, cache, adminConfig)

          // 1. Criar rota via POST
          createReqPayload = CreateRouteRequest(
            id = Some("test-route"),
            prefix = "/api/v1/test",
            methods = List("GET", "POST"),
            targetUri = "http://localhost:9999",
            stripPrefix = true,
            enabled = true
          )
          postReq = Request[IO](Method.POST, uri"/routes")
            .putHeaders(authHeader)
            .withEntity(createReqPayload)
          postResp <- routes.run(postReq).value
          _ = assert(postResp.isDefined)
          _ = assertEquals(postResp.get.status, Status.Created)

          // Verifica se o cache foi atualizado no mesmo instante
          cachedAfterPost <- cache.getRoutes
          _ = assert(cachedAfterPost.exists(_.id == "test-route"))

          // 2. Buscar por ID via GET
          getReq = Request[IO](Method.GET, uri"/routes/test-route").putHeaders(authHeader)
          getResp <- routes.run(getReq).value
          _ = assert(getResp.isDefined)
          _ = assertEquals(getResp.get.status, Status.Ok)

          // 3. Atualizar rota via PUT
          updateReqPayload = UpdateRouteRequest(
            prefix = "/api/v1/updated",
            methods = List("GET"),
            targetUri = "http://localhost:8888",
            stripPrefix = false,
            enabled = true
          )
          putReq = Request[IO](Method.PUT, uri"/routes/test-route")
            .putHeaders(authHeader)
            .withEntity(updateReqPayload)
          putResp <- routes.run(putReq).value
          _ = assert(putResp.isDefined)
          _ = assertEquals(putResp.get.status, Status.Ok)

          // Verifica se o cache refletiu a atualização
          cachedAfterPut <- cache.getRoutes
          _ = assert(cachedAfterPut.find(_.id == "test-route").exists(_.prefix.renderString == "/api/v1/updated"))

          // 4. Forçar reload via POST /reload
          reloadReq = Request[IO](Method.POST, uri"/routes/reload").putHeaders(authHeader)
          reloadResp <- routes.run(reloadReq).value
          _ = assert(reloadResp.isDefined)
          _ = assertEquals(reloadResp.get.status, Status.Ok)

          // 5. Deletar rota via DELETE
          deleteReq = Request[IO](Method.DELETE, uri"/routes/test-route").putHeaders(authHeader)
          deleteResp <- routes.run(deleteReq).value
          _ = assert(deleteResp.isDefined)
          _ = assertEquals(deleteResp.get.status, Status.NoContent)

          // Cache deve estar sem a rota após a remoção
          cachedAfterDelete <- cache.getRoutes
          _ = assert(!cachedAfterDelete.exists(_.id == "test-route"))
        } yield ()
      }
    } yield ()
  }
