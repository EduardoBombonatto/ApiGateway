package com.example.apigateway.observability

import cats.effect.Sync
import cats.syntax.all._
import io.prometheus.client.exporter.common.TextFormat
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci._

object MetricsRoutes:

  def apply[F[_]: Sync](metricsService: MetricsService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root =>
        metricsService.scrape().flatMap { content =>
          Ok(content).map(_.putHeaders(Header.Raw(ci"Content-Type", TextFormat.CONTENT_TYPE_004)))
        }
      case GET -> Root / "metrics" =>
        metricsService.scrape().flatMap { content =>
          Ok(content).map(_.putHeaders(Header.Raw(ci"Content-Type", TextFormat.CONTENT_TYPE_004)))
        }
    }
