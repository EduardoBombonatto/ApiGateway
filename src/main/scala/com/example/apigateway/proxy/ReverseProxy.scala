package com.example.apigateway.proxy

import cats.effect.Async
import cats.syntax.all._
import com.example.apigateway.discovery.LoadBalancer
import com.example.apigateway.discovery.model.ServiceInstance
import com.example.apigateway.model.Route
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers._
import org.typelevel.ci._

import java.io.IOException
import java.net.{ConnectException, UnknownHostException}
import java.util.concurrent.TimeoutException

/** Contrato do serviço de proxy reverso. */
trait ReverseProxy[F[_]]:
  def forward(route: Route, req: Request[F]): F[Response[F]]

object ReverseProxy:

  private val HopByHopHeaders: Set[CIString] = Set(
    ci"connection",
    ci"keep-alive",
    ci"proxy-authenticate",
    ci"proxy-authorization",
    ci"te",
    ci"trailers",
    ci"transfer-encoding",
    ci"upgrade"
  )

  /** Cria uma instância de ReverseProxy[F] vinculada ao Client e opcionalmente ao LoadBalancer. */
  def apply[F[_]: Async](
      client: Client[F],
      loadBalancer: Option[LoadBalancer[F]] = None
  ): ReverseProxy[F] = new ReverseProxy[F]:
    override def forward(route: Route, req: Request[F]): F[Response[F]] =
      ReverseProxy.forward(route, req, client, loadBalancer)

  /** Sobrecarga para conveniência que recebe diretamente a trait LoadBalancer. */
  def apply[F[_]: Async](client: Client[F], loadBalancer: LoadBalancer[F]): ReverseProxy[F] =
    apply(client, Some(loadBalancer))

  /** Encaminha a requisição recebida para o upstream definido na rota.
    * Se o esquema for lb://, consulta o LoadBalancer para resolver o nó ativo antes de despachar.
    */
  def forward[F[_]: Async](
      route: Route,
      req: Request[F],
      client: Client[F],
      loadBalancer: Option[LoadBalancer[F]] = None
  ): F[Response[F]] =
    if isLoadBalanced(route.targetUri) then
      val serviceName = extractServiceName(route.targetUri)
      loadBalancer match
        case Some(lb) if serviceName.nonEmpty =>
          lb.choose(serviceName).flatMap {
            case Some(instance) =>
              val resolvedRoute = resolveRouteWithInstance(route, instance)
              executeForward(resolvedRoute, req, client)
            case None =>
              Response[F](Status.ServiceUnavailable)
                .withEntity(
                  s"""{"error":"Service Unavailable","message":"Nenhuma instância saudável encontrada no Eureka para o serviço '$serviceName'."}"""
                )
                .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
                .pure[F]
          }
        case _ =>
          Response[F](Status.ServiceUnavailable)
            .withEntity(
              s"""{"error":"Service Unavailable","message":"LoadBalancer não configurado ou nome de serviço inválido para '$serviceName'."}"""
            )
            .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
            .pure[F]
    else
      executeForward(route, req, client)

  private def executeForward[F[_]: Async](
      route: Route,
      req: Request[F],
      client: Client[F]
  ): F[Response[F]] =
    val targetUri = buildTargetUri(route, req.uri)
    val outboundHeaders = buildOutboundHeaders(route, req)

    val outboundReq = Request[F](
      method = req.method,
      uri = targetUri,
      headers = outboundHeaders,
      body = req.body
    )

    client
      .run(outboundReq)
      .allocated
      .flatMap { case (upstreamResp, release) =>
        val cleanedRespHeaders = filterHeaders(upstreamResp.headers)
        val streamingBody = upstreamResp.body.onFinalize(release)

        Response[F](
          status = upstreamResp.status,
          headers = cleanedRespHeaders,
          body = streamingBody
        ).pure[F]
      }
      .handleErrorWith {
        case _: TimeoutException =>
          Response[F](Status.GatewayTimeout)
            .withEntity(
              """{"error":"Gateway Timeout","message":"O serviço de destino demorou a responder."}"""
            )
            .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
            .pure[F]

        case e: (ConnectException | UnknownHostException | IOException) =>
          Response[F](Status.BadGateway)
            .withEntity(
              s"""{"error":"Bad Gateway","message":"Falha de conexão com o serviço de destino: ${e.getMessage}"}"""
            )
            .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
            .pure[F]

        case other =>
          Response[F](Status.BadGateway)
            .withEntity(
              s"""{"error":"Bad Gateway","message":"Erro inesperado ao contactar upstream: ${other.getMessage}"}"""
            )
            .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
            .pure[F]
      }

  private def isLoadBalanced(uri: Uri): Boolean =
    uri.scheme.exists(_.value.equalsIgnoreCase("lb"))

  private def extractServiceName(uri: Uri): String =
    uri.host.map(_.value).orElse(uri.authority.map(_.host.value)).getOrElse("")

  private def resolveRouteWithInstance(route: Route, instance: ServiceInstance): Route =
    val resolvedScheme = if instance.secure then Uri.Scheme.https else Uri.Scheme.http
    val resolvedAuthority = Uri.Authority(
      host = Uri.RegName(instance.host),
      port = Some(instance.port)
    )
    val resolvedTarget = route.targetUri.copy(
      scheme = Some(resolvedScheme),
      authority = Some(resolvedAuthority)
    )
    route.copy(targetUri = resolvedTarget)

  /** Constrói a nova URI de destino combinando o host do upstream com o path ajustado. */
  def buildTargetUri(route: Route, incomingUri: Uri): Uri =
    val remainingSegments =
      if route.stripPrefix then
        val prefixLen = route.prefix.segments.filter(_.encoded.nonEmpty).length
        val incomingCleanSegments = incomingUri.path.segments.filter(_.encoded.nonEmpty)
        incomingCleanSegments.drop(prefixLen)
      else
        incomingUri.path.segments.filter(_.encoded.nonEmpty)

    val baseSegments = route.targetUri.path.segments.filter(_.encoded.nonEmpty)
    val finalSegments = baseSegments ++ remainingSegments

    val newPath = Uri.Path(
      segments = finalSegments,
      absolute = true
    )

    route.targetUri.copy(
      path = newPath,
      query = incomingUri.query
    )

  /** Higieniza e injeta os headers necessários para a requisição de saída. */
  private def buildOutboundHeaders[F[_]](route: Route, req: Request[F]): Headers =
    // Remove cabeçalhos de salto (hop-by-hop) e o Host original
    val withoutHopByHop = req.headers.headers.filterNot { h =>
      HopByHopHeaders.contains(h.name) || h.name == ci"host"
    }

    // Identifica o IP do cliente
    val clientIp = req.remoteAddr.map(_.toString).orElse(req.from.map(_.toInetAddress.getHostAddress))

    // Calcula X-Forwarded-For adicionando à cadeia existente
    val forwardedForHeader = req.headers.get(ci"x-forwarded-for") match
      case Some(existing) =>
        clientIp match
          case Some(ip) => Header.Raw(ci"x-forwarded-for", s"${existing.head.value}, $ip")
          case None     => Header.Raw(ci"x-forwarded-for", existing.head.value)
      case None =>
        Header.Raw(ci"x-forwarded-for", clientIp.getOrElse("unknown"))

    val forwardedProtoHeader = Header.Raw(
      ci"x-forwarded-proto",
      req.uri.scheme.map(_.value).getOrElse("http")
    )

    val forwardedHostHeader = Header.Raw(
      ci"x-forwarded-host",
      req.headers.get[Host].map(h => s"${h.host}${h.port.fold("")(p => s":$p")}").getOrElse(req.uri.authority.map(_.toString).getOrElse(""))
    )

    // Ajusta o cabeçalho Host para o host/porta do upstream
    val upstreamHostHeader = route.targetUri.host.map { h =>
      val portStr = route.targetUri.port.fold("")(p => s":$p")
      Header.Raw(ci"host", s"${h.value}$portStr")
    }

    val extraHeaders = List(
      Some(forwardedForHeader),
      Some(forwardedProtoHeader),
      Some(forwardedHostHeader),
      upstreamHostHeader
    ).flatten

    Headers(withoutHopByHop ++ extraHeaders)

  /** Remove cabeçalhos hop-by-hop da resposta antes de devolver ao cliente. */
  private def filterHeaders(headers: Headers): Headers =
    Headers(headers.headers.filterNot(h => HopByHopHeaders.contains(h.name)))
