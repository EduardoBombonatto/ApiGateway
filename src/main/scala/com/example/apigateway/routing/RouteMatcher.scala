package com.example.apigateway.routing

import com.example.apigateway.model.Route
import org.http4s.{Method, Request, Uri}

sealed trait MatchResult

object MatchResult:
  final case class Matched(route: Route) extends MatchResult
  final case class MethodNotAllowed(allowedMethods: Set[Method]) extends MatchResult
  case object NotFound extends MatchResult

object RouteMatcher:

  /** Avalia a requisição contra a lista de rotas conhecidas.
    *
    * Regras de resolução:
    * 1. Rotas com caminhos mais específicos (mais segmentos) têm precedência.
    * 2. Se houver rota compatível com o caminho e o método, retorna [[MatchResult.Matched]].
    * 3. Se houver correspondência de caminho, mas o método não for suportado por nenhuma das
    *    rotas daquele prefixo mais específico, retorna [[MatchResult.MethodNotAllowed]]
    *    com o conjunto de métodos aceitos.
    * 4. Se nenhum caminho for compatível, retorna [[MatchResult.NotFound]].
    */
  def findMatch[F[_]](routes: List[Route], req: Request[F]): MatchResult =
    val reqSegments = req.uri.path.segments

    // Ordena por especificidade (maior número de segmentos primeiro)
    val sortedRoutes = routes.sortBy(-_.prefix.segments.length)

    // Filtra todas as rotas cujo prefixo seja compatível com os segmentos da requisição
    val pathMatches = sortedRoutes.filter(r => matchesPath(r.prefix, reqSegments))

    if pathMatches.isEmpty then
      MatchResult.NotFound
    else
      // Identifica o comprimento do prefixo mais específico que deu match
      val mostSpecificLength = pathMatches.head.prefix.segments.length
      val candidatesForMostSpecific = pathMatches.filter(_.prefix.segments.length == mostSpecificLength)

      // Procura primeiro candidato que aceita o método da requisição
      candidatesForMostSpecific.find(r => acceptsMethod(r, req.method)) match
        case Some(route) =>
          MatchResult.Matched(route)
        case None =>
          // Nenhum aceitou o método: compõe os métodos aceitos
          val allowed = candidatesForMostSpecific.flatMap(_.methods).toSet
          if allowed.nonEmpty then
            MatchResult.MethodNotAllowed(allowed)
          else
            MatchResult.NotFound

  private def matchesPath(prefix: Uri.Path, reqSegments: Vector[Uri.Path.Segment]): Boolean =
    val prefixSegments = prefix.segments.filter(_.encoded.nonEmpty)
    if prefixSegments.isEmpty then true
    else reqSegments.startsWith(prefixSegments)

  private def acceptsMethod(route: Route, method: Method): Boolean =
    route.methods.isEmpty || route.methods.contains(method)
