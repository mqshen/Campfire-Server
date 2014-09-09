package campfire.socketio

import akka.actor._
import akka.contrib.pattern.DistributedPubSubMediator._
import scala.collection.concurrent

/**
 * Created by goldratio on 9/8/14.
 */

object LocalMediator {
  def props() = Props(classOf[LocalMediator])

  private val topicToSubscriptions = concurrent.TrieMap[String, Set[ActorRef]]()

//
//  final case class Subscribe(channel: Subject[OnData])
//  final case class SubscribeAck(subcribe: Subscribe)
//  final case class Unsubscribe(channel: Option[Subject[OnData]])
//  final case class UnsubscribeAck(subcribe: Unsubscribe)
//
//  sealed trait OnData {
//    def context: ConnectionContext
//    def packet: Packet
//
//    final def sessionId = context.sessionId
//
//    import ConnectionActive._
//
//    def replyMessage(msg: String)(implicit resolver: ActorRef) =
//      resolver ! SendMessage(sessionId, msg)
//
//    def replyJson(json: String)(implicit resolver: ActorRef) =
//      resolver ! SendJson(sessionId, json)
//
//    def replyEvent(name: String, args: String)(implicit resolver: ActorRef) =
//      resolver ! SendEvent(sessionId, name, Left(args))
//
//    def replyEvent(name: String, args: Seq[String])(implicit resolver: ActorRef) =
//      resolver ! SendEvent(sessionId, name, Right(args))
//
//    def reply(packets: Packet*)(implicit resolver: ActorRef) =
//      resolver ! SendPackets(sessionId, packets)
//
//    def ack(args: String)(implicit resolver: ActorRef) =
//      resolver ! SendAck(sessionId, packet.asInstanceOf[DataPacket], args)
//
//    /**
//     * @param room    room to broadcast
//     * @param packet  packet to broadcast
//     */
//    def broadcast(room: String, packet: Packet)(implicit resolver: ActorRef) =
//      resolver ! Broadcast(sessionId, room, packet)
//  }
//  final case class OnConnect(args: Seq[(String, String)], context: ConnectionContext)(implicit val packet: ConnectPacket) extends OnData
//  final case class OnDisconnect(context: ConnectionContext)(implicit val packet: DisconnectPacket) extends OnData
//  final case class OnMessage(msg: String, context: ConnectionContext)(implicit val packet: MessagePacket) extends OnData
//  final case class OnJson(json: String, context: ConnectionContext)(implicit val packet: JsonPacket) extends OnData
//  final case class OnEvent(name: String, args: String, context: ConnectionContext)(implicit val packet: EventPacket) extends OnData
}

class LocalMediator extends Actor with ActorLogging {
  import LocalMediator._

  def subscriptionsFor(topic: String): Set[ActorRef] = {
    topicToSubscriptions.getOrElseUpdate(topic, Set[ActorRef]())
  }

  def receive: Receive = {
    case x @ Subscribe(topic, _, subscriptions) =>
      val subs = subscriptionsFor(topic)
      topicToSubscriptions(topic) = subs + subscriptions
      context.watch(subscriptions)
      sender() ! SubscribeAck(x)

    case Unsubscribe(topic, _, subscriptions) =>
      topicToSubscriptions.get(topic) match {
        case Some(xs) =>
          val subs = xs - subscriptions
          if (subs.isEmpty) {
            topicToSubscriptions -= topic
          } else {
            topicToSubscriptions(topic) = subs
          }

        case None =>
      }

    case Terminated(ref) =>
      var topicsToRemove = List[String]()
      for { (topic, xs) <- topicToSubscriptions } {
        val subs = xs - ref
        if (subs.isEmpty) {
          topicsToRemove ::= topic
        } else {
          topicToSubscriptions(topic) = subs
        }
      }
      topicToSubscriptions --= topicsToRemove

    case Publish(topic: String, msg: Any, sendOneMessageToEachGroup) =>
      topicToSubscriptions.get(topic) foreach { subs => subs foreach (_ ! msg) }
  }
}
