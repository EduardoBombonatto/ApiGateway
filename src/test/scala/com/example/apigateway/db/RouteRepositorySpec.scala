package com.example.apigateway.db

import cats.effect.{IO, Resource}
import com.example.apigateway.config.DbConfig
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite

class RouteRepositorySpec extends CatsEffectSuite:

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

  test("DatabaseMigrator deve aplicar migrations Flyway e RouteRepository deve persistir e consultar rotas reais") {
    for {
      migrateResult <- DatabaseMigrator.migrate[IO](dbConfig)
      _ = assert(migrateResult.migrationsExecuted >= 0)

      _ <- transactorResource.use { xa =>
        val repo = RouteRepository[IO](xa)

        val record = RouteRecord(
          id = "real-postgres-route",
          prefix = "/api/v1/real",
          methods = List("GET", "POST"),
          targetUri = "https://httpbin.org",
          stripPrefix = true,
          enabled = true
        )

        for {
          // Limpa se já existir de teste anterior
          _ <- repo.delete("real-postgres-route")

          // Insere rota
          saved <- repo.insert(record)
          _ = assertEquals(saved.id, "real-postgres-route")

          // Busca por ID
          foundOpt <- repo.findById("real-postgres-route")
          _ = assert(foundOpt.isDefined)
          _ = assertEquals(foundOpt.get.prefix, "/api/v1/real")
          _ = assertEquals(foundOpt.get.methods, List("GET", "POST"))

          // Lista ativas convertendo para domínio
          activeRoutes <- repo.findAllActive
          _ = assert(activeRoutes.exists(_.id == "real-postgres-route"))

          // Atualiza registro
          updatedOpt <- repo.update(
            "real-postgres-route",
            record.copy(prefix = "/api/v1/updated-real", enabled = false)
          )
          _ = assert(updatedOpt.isDefined)
          _ = assertEquals(updatedOpt.get.prefix, "/api/v1/updated-real")

          // Ao desativar, não deve aparecer em findAllActive
          activeAfterDisable <- repo.findAllActive
          _ = assert(!activeAfterDisable.exists(_.id == "real-postgres-route"))

          // Remove registro
          deleted <- repo.delete("real-postgres-route")
          _ = assert(deleted)

          // Confirma remoção
          afterDelete <- repo.findById("real-postgres-route")
          _ = assertEquals(afterDelete, None)
        } yield ()
      }
    } yield ()
  }
