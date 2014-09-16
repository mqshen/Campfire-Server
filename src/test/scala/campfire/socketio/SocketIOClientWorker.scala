package campfire.socketio

/**
 * Created by goldratio on 9/16/14.
 */
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import org.parboiled.errors.ParsingException
import scala.util.Failure
import scala.util.Success
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.CloseFrame
import spray.can.websocket.frame.StatusCode
import spray.can.websocket.frame.TextFrame
import campfire.socketio
import campfire.socketio.packet.AckPacket
import campfire.socketio.packet.ConnectPacket
import campfire.socketio.packet.DataPacket
import campfire.socketio.packet.DisconnectPacket
import campfire.socketio.packet.HeartbeatPacket
import campfire.socketio.packet.Packet
import campfire.socketio.packet.PacketParser
import spray.http._

object SocketIOClientWorker {
  type AckPostAction = Any => Unit
  final case class SendPacket(packet: Packet)
  final case class SendPacketWithAck(packet: DataPacket, ackAction: AckPostAction = _ => ())
}
trait SocketIOClientWorker extends ActorLogging { _: Actor =>
  import SocketIOClientWorker._

  def sessionId: String

  private var _connection: ActorRef = _
  /**
   * The actor which could receive frame directly. ie. by
   *   connection ! frame
   */
  def connection = _connection

  private var idToAckAction = Map[Long, AckPostAction]()

  def receive = handleHandshake orElse handleTeminate

  def handleTeminate: Receive = {
    case ev: Http.ConnectionClosed =>
      context.stop(self)
      log.debug("Connection closed on event: {}", ev)
  }

  def handleHandshake: Receive = {
    case Http.Connected(remoteAddress, localAddress) =>
      val host = remoteAddress.getHostName
      val port = remoteAddress.getPort
      val headers:List[HttpHeader] = List(HttpHeaders.Host(host, port), HttpHeaders.Cookie(HttpCookie("session", sessionId)))
      val authrity = Uri.Authority(Uri.NamedHost(host), port)
      val uri = Uri("http", Uri.Authority(Uri.NamedHost(host), port), Uri.Path("/" + socketio.SOCKET_IO + "/1/"))
      val socketioHandshake = HttpRequest(uri = uri, headers = headers)
      sender() ! socketioHandshake
      log.debug("Sent socket.io handshake request: {}", socketioHandshake)

    case socketio.HandshakeResponse(socketio.HandshakeContext(response, sessionId, heartbeatTimeout, closeTimeout)) =>
      val wsUpgradeRequest = websocket.basicHandshakeRepuset("/" + socketio.SOCKET_IO + "/1/websocket/" + sessionId)
      val upgradePipelineStage = { response: HttpResponse =>
        response match {
          case websocket.HandshakeResponse(state) =>
            state match {
              case wsFailure: websocket.HandshakeFailure => None
              case wsContext: websocket.HandshakeContext => Some(websocket.clientPipelineStage(self, wsContext))
            }
        }
      }
      sender() ! UHttp.UpgradeClient(upgradePipelineStage, wsUpgradeRequest)

    case UHttp.Upgraded =>
      // this is the proper actor that could receive frame sent to it directly
      // @see WebSocketFrontend#receiverRef
      _connection = sender()

    case TextFrame(payload) =>
      PacketParser(payload) match {
        case Success(packets) =>
          packets.headOption match {
            case Some(ConnectPacket(_)) =>
              onOpen()
              context.become(businessLogic orElse handleSocketio orElse handleTeminate)
            case _ =>
          }
        case Failure(ex: ParsingException) =>
          log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
          connection ! CloseFrame(StatusCode.InternalError, "Invalide socket.io packet")
        case Failure(ex) =>
          log.warning("Exception during parse socket.io packet: {} ..., due to: {}", payload.take(50).utf8String, ex)
      }
  }

  def handleSocketio: Receive = {
    case TextFrame(payload) =>
      PacketParser(payload) match {
        case Success(packets) =>
          packets foreach {
            case ConnectPacket(args) => onConnected(args)
            case DisconnectPacket(args)    => onDisconnected(args)
            case HeartbeatPacket               => connection ! TextFrame(HeartbeatPacket.utf8String)

            case AckPacket(id, args) =>
              idToAckAction.get(id) foreach { _(args) }
              idToAckAction -= id
              onAck(id, args)

            case packet =>
              log.debug("Got {}", packet)
              onPacket(packet)
          }

        case Failure(ex: ParsingException) =>
          log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
          connection ! CloseFrame(StatusCode.InternalError, "Invalide socket.io packet")
        case Failure(ex) =>
          log.warning("Exception during parse socket.io packet: {} ..., due to: {}", payload.take(50).utf8String, ex)
      }

    // -- sending logic

    case SendPacket(packet) => connection ! TextFrame(packet.render)

    case SendPacketWithAck(packet, ackAction) =>
      idToAckAction += (packet.id -> ackAction)
      connection ! TextFrame(packet.render)
  }

  def businessLogic: Receive

  def onOpen() {

  }

  def onConnected(args: Seq[(String, String)]) {

  }

  def onDisconnected(args: Seq[(String, String)]) {

  }

  def onAck(id: Long, args: String) {

  }

  def onPacket(packet: Packet)

}
