package com.example.apigateway.cache

import cats.effect.{IO, Resource}
import com.example.apigateway.config.DbConfig
import com.example.apigateway.db.{DatabaseMigrator, RouteRecord, RouteRepository}
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite

class RouteCacheSpec extends CatsEffectSuite:

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

  test("RouteCache deve carregar rotas ativas na inicialização e atualizar atomicamente no reload") {
    for {
      _ <- DatabaseMigrator.migrate[IO](dbConfig)

      _ <- transactorResource.use { xa =>
        val repo = RouteRepository[IO](xa)

        for {
          _ <- repo.delete("r1")
          _ <- repo.delete("r2")

          // Insere rota inicial
          r1 = RouteRecord(
            id = "r1",
            prefix = "/api/v1/users",
            methods = List("GET"),
            targetUri = "http://localhost:9001",
            enabled = true
          )
          _ <- repo.insert(r1)

          // Inicializa cache a partir da trait RouteRepository
          cache <- RouteCache[IO](repo)
          initialRoutes <- cache.getRoutes
          _ = assert(initialRoutes.exists(_.id == "r1"))

          // Insere segunda rota diretamente no banco
          r2 = RouteRecord(
            id = "r2",
            prefix = "/api/v1/orders",
            methods = List("POST"),
            targetUri = "http://localhost:9002",
            enabled = true
          )
          _ <- repo.insert(r2)

          // Antes do reload, o cache permanece intacto (zero I/O com banco no cache)
          cachedBeforeReload <- cache.getRoutes
          _ = assert(!cachedBeforeReload.exists(_.id == "r2"))

          // Dispara reload no cache
          _ <- cache.reload
          cachedAfterReload <- cache.getRoutes
          _ = assert(cachedAfterReload.exists(_.id == "r1"))
          _ = assert(cachedAfterReload.exists(_.id == "r2"))

          // Desativa r1 no banco
          _ <- repo.update("r1", r1.copy(enabled = false))
          _ <- cache.reload
          cachedAfterDisable <- cache.getRoutes
          _ = assert(!cachedAfterDisable.exists(_.id == "r1"))
          _ = assert(cachedAfterDisable.exists(_.id == "r2"))

          // Limpeza
          _ <- repo.delete("r1")
          _ <- repo.delete("r2")
        } yield ()
      }
    } yield ()
  }
