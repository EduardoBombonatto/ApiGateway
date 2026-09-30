package com.example.apigateway.db

import cats.effect.Sync
import com.example.apigateway.config.DbConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

object DatabaseMigrator:

  /** Executa as migrações pendentes do Flyway de forma bloqueante segura em Sync[F].blocking. */
  def migrate[F[_]: Sync](dbConfig: DbConfig): F[MigrateResult] =
    Sync[F].blocking {
      val flyway = Flyway
        .configure()
        .dataSource(dbConfig.url, dbConfig.user, dbConfig.password)
        .locations("classpath:db/migration")
        .load()

      flyway.migrate()
    }
