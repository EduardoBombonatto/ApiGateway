package com.example.apigateway.observability

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import org.http4s._
import org.typelevel.ci._

import java.util.UUID

/** Contrato funcional para middleware de Tracing e propagação do Correlation ID. */
trait TracingMiddleware[F[_]]:
  def trace(httpRoutes: HttpRoutes[F]): HttpRoutes[F]
  def extractOrGenerate(req: Request[F]): (String, Request[F])

object TracingMiddleware:

  val CorrelationIdHeader: CIString = ci"X-Correlation-ID"

  def apply[F[_]: Sync]: TracingMiddleware[F] = new TracingMiddleware[F]:

    override def extractOrGenerate(req: Request[F]): (String, Request[F]) =
      req.headers.get(CorrelationIdHeader) match
        case Some(existing) =>
          (existing.head.value, req)
        case None =>
          val newId = UUID.randomUUID().toString
          val enrichedReq = req.putHeaders(Header.Raw(CorrelationIdHeader, newId))
          (newId, enrichedReq)

    override def trace(httpRoutes: HttpRoutes[F]): HttpRoutes[F] =
      Kleisli { (req: Request[F]) =>
        val (cid, enrichedReq) = extractOrGenerate(req)
        httpRoutes.run(enrichedReq).map { resp =>
          resp.putHeaders(Header.Raw(CorrelationIdHeader, cid))
        }
      }
