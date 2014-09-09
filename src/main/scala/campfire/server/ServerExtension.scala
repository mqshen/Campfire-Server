package campfire.server

import akka.actor._
import akka.pattern.ask
import akka.contrib.pattern.DistributedPubSubMediator
import akka.util.Timeout
import campfire.server.ServerExtension.{Subscribe, OnData}
import campfire.socketio
import campfire.socketio.ConnectionActive.OnPacket
import campfire.socketio.packet._
import campfire.socketio.{ConnectionActive, ConnectionContext, SocketIOExtension}
import rx.lang.scala.Subject

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Created by goldratio on 9/8/14.
 */
object ServerExtension extends ExtensionId[ServerExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): ServerExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = ServerExtension

  override def createExtension(system: ExtendedActorSystem): ServerExtension = new ServerExtension(system)


  final case class Subscribe(channel: Subject[OnData])
  final case class SubscribeAck(subcribe: Subscribe)
  final case class Unsubscribe(channel: Option[Subject[OnData]])
  final case class UnsubscribeAck(subcribe: Unsubscribe)

  sealed trait OnData {
    def context: ConnectionContext
    def packet: Packet

    final def sessionId = context.sessionId

    import ConnectionActive._

    def replyMessage(msg: String)(implicit resolver: ActorRef) =
      resolver ! SendMessage(sessionId, msg)

    def replyJson(json: String)(implicit resolver: ActorRef) =
      resolver ! SendJson(sessionId, json)

    def replyEvent(name: String, args: String)(implicit resolver: ActorRef) =
      resolver ! SendEvent(sessionId, name, Left(args))

    def replyEvent(name: String, args: Seq[String])(implicit resolver: ActorRef) =
      resolver ! SendEvent(sessionId, name, Right(args))

    def reply(packets: Packet*)(implicit resolver: ActorRef) =
      resolver ! SendPackets(sessionId, packets)

    def ack(args: String)(implicit resolver: ActorRef) =
      resolver ! SendAck(sessionId, packet.asInstanceOf[DataPacket], args)

    /**
     * @param room    room to broadcast
     * @param packet  packet to broadcast
     */
    def broadcast(room: String, packet: Packet)(implicit resolver: ActorRef) =
      resolver ! Broadcast(sessionId, room, packet)
  }
  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val packet: ConnectPacket) extends OnData
  final case class OnDisconnect(context: ConnectionContext)(implicit val packet: DisconnectPacket) extends OnData
  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val packet: MessagePacket) extends OnData
  final case class OnJson(json: String, context: ConnectionContext)(implicit val packet: JsonPacket) extends OnData
  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val packet: EventPacket) extends OnData

}

class ServerExtension(system: ExtendedActorSystem) extends Extension {

  lazy val resolver = SocketIOExtension(system).resolver

  val mediator =  SocketIOExtension(system).localMediator

  val serverActor = system.actorOf(CampfireServerActor.props(mediator), name = CampfireServerActor.shareName)


}

object CampfireServerActor {
  val shareName = "campfire-server-channel"
  def props(mediator: ActorRef) = Props(classOf[CampfireServerActor], mediator)
}

class CampfireServerActor(mediator: ActorRef) extends Actor with ActorLogging {

  import campfire.server.ServerExtension._

  var channel = Subject[OnData]()

  private var isMediatorSubscribed: Boolean = _
  def subscribeMediatorForNamespace(action: () => Unit) = {
    if (!isMediatorSubscribed) {
      import context.dispatcher
      implicit val timeout = Timeout(socketio.namespaceSubscribeTimeout)
      val f1 = mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForDisconnect, self)).mapTo[DistributedPubSubMediator.SubscribeAck]
      val f2 = mediator.ask(DistributedPubSubMediator.Subscribe(socketio.topicForClinet, self)).mapTo[DistributedPubSubMediator.SubscribeAck]
      Future.sequence(List(f1, f2)).onComplete {
        case Success(ack) =>
          isMediatorSubscribed = true
          action()
        case Failure(ex) =>
          log.warning("Failed to subscribe to mediator on topic {}: {}", socketio.topicForClinet, ex.getMessage)
      }
    } else {
      action()
    }
  }

  def receive: Receive = {
    case x @ Subscribe(channel) =>
      val commander = sender()
      subscribeMediatorForNamespace { () =>
        this.channel = channel
        commander ! SubscribeAck(x)
      }

    case OnPacket(packet: ConnectPacket, connContext)    =>
      channel.onNext(OnConnect(packet.args, connContext)(packet))
    case OnPacket(packet: DisconnectPacket, connContext) =>
      channel.onNext(OnDisconnect(connContext)(packet))
    case OnPacket(packet: MessagePacket, connContext)    =>
      channel.onNext(OnMessage(packet.data, connContext)(packet))
    case OnPacket(packet: JsonPacket, connContext)       =>
      channel.onNext(OnJson(packet.json, connContext)(packet))
    case OnPacket(packet: EventPacket, connContext)      =>
      channel.onNext(OnEvent(packet.name, packet.args, connContext)(packet))
  }

}