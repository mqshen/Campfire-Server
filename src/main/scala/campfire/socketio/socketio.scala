package campfire

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import campfire.socketio.transport
import com.typesafe.config.{ Config, ConfigFactory }
import spray.can.Http
import spray.can.websocket.frame.TextFrame
import spray.http.HttpHeaders.Origin
import spray.http._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.concurrent.duration._
/**
 * Created by goldratio on 9/8/14.
 */
package object socketio {
  val SOCKET_IO = "socket.io"

  val config = ConfigFactory.load().getConfig("spray.socketio")
  val Settings = new Settings(config)
  class Settings(config: Config) {
    val supportedTransports = config.getString("server.supported-transports")
    val heartbeatInterval = config.getInt("server.heartbeat-interval")
    val HeartbeatTimeout = config.getInt("server.heartbeat-timeout")
    val CloseTimeout = config.getInt("server.close-timeout")
    val IdleTimeout = config.getInt("server.idle-timeout")
    val namespacesDispatcher = config.getString("namespaces-dispatcher")
    val namespaceDispatcher = config.getString("namespace-dispatcher")
  }

  val actorResolveTimeout = config.getInt("server.actor-selection-resolve-timeout").seconds
  val namespaceSubscribeTimeout = config.getInt("server.namespace-subscribe-timeout").seconds

  val topicForClinet = "socketio-server-global-client"
  val topicForDisconnect = "socketio-global-disconnect"

  final class SoConnectingContext(var sessionId: String,
                                  val sessionIdGenerator: HttpRequest => Future[String],
                                  val serverConnection: ActorRef,
                                  val socketioWorker: ActorRef,
                                  val resolver: ActorRef,
                                  val log: LoggingAdapter,
                                  implicit val ec: ExecutionContext)

  object HandshakeRequest {
    def unapply(req: HttpRequest)(implicit ctx: SoConnectingContext): Option[Boolean] = req match {
      case HttpRequest(_, uri, headers, _, _) =>
        uri.path.toString.split("/") match {
          case Array("", SOCKET_IO, protocalVersion) =>
            import ctx.ec
            ctx.sessionIdGenerator(req) onComplete {
              case Success(sessionId) =>
                ctx.resolver ! ConnectionActive.CreateSession(sessionId)

                val origins = headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
                val originsHeaders = List(
                  HttpHeaders.`Access-Control-Allow-Origin`(SomeOrigins(origins)),
                  HttpHeaders.`Access-Control-Allow-Credentials`(true))

                val respHeaders = List(HttpHeaders.Connection("keep-alive")) ::: originsHeaders
                val respEntity = List(sessionId, Settings.HeartbeatTimeout, Settings.CloseTimeout, Settings.supportedTransports).mkString(":")
                val resp = HttpResponse(
                  status = StatusCodes.OK,
                  entity = respEntity,
                  headers = respHeaders)

                println(resp)
                ctx.serverConnection ! Http.MessageCommand(resp)

              case Failure(ex) =>
            }

            Some(true)

          case _ => None
        }

      case _ => None
    }
  }

  final case class HandshakeContext(response: HttpResponse, sessionId: String, heartbeatTimeout: Int, closeTimeout: Int)
  /**
   * Response that socket.io client got during socket.io handshake
   */
  object HandshakeResponse {
    def unapply(resp: HttpResponse): Option[HandshakeContext] = resp match {
      case HttpResponse(StatusCodes.OK, entity, headers, _) =>
        entity.asString.split(":") match {
          case Array(sessionId, heartbeatTimeout, closeTimeout, supportedTransports, _*) if supportedTransports.split(",").map(_.trim).contains(transport.WebSocket.ID) =>
            Some(HandshakeContext(resp, sessionId, heartbeatTimeout.toInt, closeTimeout.toInt))
          case _ => None
        }

      case _ => None
    }
  }

  def wsConnecting(req: HttpRequest)(implicit ctx: SoConnectingContext): Option[Boolean] = {
    val query = req.uri.query
    val origins = req.headers.collectFirst { case Origin(xs) => xs } getOrElse (Nil)
    req.uri.path.toString.split("/") match {
      case Array("", SOCKET_IO, protocalVersion, transport.WebSocket.ID, sessionId) =>
        ctx.sessionId = sessionId
        import ctx.ec
        ctx.resolver ! ConnectionActive.Connecting(sessionId, query, origins, ctx.serverConnection, transport.WebSocket)
        Some(true)
      case _ =>
        None
    }
  }

  object WsFrame {
    def unapply(frame: TextFrame)(implicit ctx: SoConnectingContext): Option[Boolean] = {
      import ctx.ec
      // ctx.sessionId should have been set during wsConnected
      ctx.log.debug("Got TextFrame: {}", frame.payload.utf8String)
      ctx.resolver ! ConnectionActive.OnFrame(ctx.sessionId, frame.payload)
      Some(true)
    }
  }

}
