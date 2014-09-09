package campfire.socketio

import akka.actor.{PoisonPill, Cancellable, ActorRef, Actor}
import akka.contrib.pattern.DistributedPubSubMediator.Publish
import akka.event.LoggingAdapter
import akka.io.Tcp
import akka.contrib.pattern.ShardRegion.Passivate
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.util.ByteString
import campfire.socketio
import campfire.socketio.packet._
import campfire.socketio.transport.Transport
import org.parboiled.errors.ParsingException
import spray.http.{HttpOrigin, Uri}
import scala.concurrent.duration._

import scala.collection.immutable
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.{Failure, Success}

/**
 * Created by goldratio on 9/8/14.
 */
object ConnectionActive {

  case object AskConnectedTime

  sealed trait Event extends Serializable

  sealed trait Command extends Serializable {
    def sessionId: String
  }

  final case class CreateSession(sessionId: String) extends Command

  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin],
                              transportConnection: ActorRef, transport: Transport) extends Command with Event
  final case class Closing(sessionId: String, transportConnection: ActorRef) extends Command with Event

  final case class OnFrame(sessionId: String, payload: ByteString) extends Command


  final case class SendMessage(sessionId: String, msg: String) extends Command
  final case class SendJson(sessionId: String, json: String) extends Command
  final case class SendEvent(sessionId: String, name: String, args: Either[String, Seq[String]]) extends Command
  final case class SendPackets(sessionId: String, packets: Seq[Packet]) extends Command


  final case class SendAck(sessionId: String, originalPacket: DataPacket, args: String) extends Command

  /**
   * ask me to publish an OnBroadcast data
   */
  final case class Broadcast(sessionId: String, room: String, packet: Packet) extends Command


  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext) extends ConsistentHashable {
    override def consistentHashKey: Any = connContext.sessionId
  }

  val shardName: String = "ConnectionActives"

  final class State(val context: ConnectionContext, var transportConnection: ActorRef, var topics: immutable.Set[String]) extends Serializable {
    override def equals(other: Any) = {
      other match {
        case x: State => x.context == this.context && x.transportConnection == this.transportConnection && x.topics == this.topics
        case _        => false
      }
    }

    override def toString = {
      new StringBuilder().append("State(")
        .append("context=").append(context)
        .append(", transConn=").append(transportConnection)
        .append(", topics=").append(topics).append(")")
        .toString
    }
  }

  val GlobalConnectPacket = ConnectPacket()
  val GlobalDisconnectPacket = DisconnectPacket()

  case object HeartbeatTick
  case object CloseTimeout
  case object IdleTimeout

  private def heartbeatDelay = ThreadLocalRandom.current.nextInt((math.min(socketio.Settings.HeartbeatTimeout, socketio.Settings.CloseTimeout) * 0.618).round.toInt).seconds
}

trait ConnectionActive { _: Actor =>
  import ConnectionActive._
  import context.dispatcher

  def mediator: ActorRef

  def log: LoggingAdapter

  def recoveryFinished: Boolean


  private lazy val scheduler = SocketIOExtension(context.system).scheduler

  private var idleTimeoutTask: Option[Cancellable] = None


  private var heartbeatTask: Option[Cancellable] = None
  private var closeTimeoutTask: Option[Cancellable] = None

  protected var pendingPackets = immutable.Queue[Packet]()

  private var _state: State = _ // have to init it lazy
  def state = {
    if (_state == null) {
      _state = new State(new ConnectionContext(), context.system.deadLetters, immutable.Set())
    }
    _state
  }
  def state_=(state: State) {
    _state = state
  }

  def working: Receive = {
    // ---- heartbeat / timeout
    case HeartbeatTick => // scheduled sending heartbeat
      log.debug("send heartbeat")
      sendPacket(HeartbeatPacket)

      // keep previous close timeout. We may skip one closetimeout for this heartbeat, but we'll reset one at next heartbeat.
      if (closeTimeoutTask.fold(true)(_.isCancelled)) {
        enableCloseTimeout()
      }

    case CloseTimeout =>
      state.transportConnection ! Tcp.Close
      log.info("CloseTimeout disconnect: {}, state: {}", state.context.sessionId, state)
      if (state.context.isConnected) {
        // make sure only send disconnect packet one time
        onPacket(null)(GlobalDisconnectPacket)
      }

    case IdleTimeout =>
      log.info("IdleTimeout stop: {}, state: {}", state.context.sessionId, state)
      doStop()

    case cmd @ Connecting(sessionId, query, origins, transportConnection, transport) => // transport fired connecting command
      disableIdleTimeout()
      disableCloseTimeout()
      enableHeartbeat()

      state.context.sessionId match {
        case null =>
          state.context.sessionId = sessionId
          state.context.query = query
          state.context.origins = origins
          state.context.transport = transport
          state.transportConnection = transportConnection

          updateState(cmd, state)
          onPacket(cmd)(GlobalConnectPacket)
        case existed =>
          state.context.transport = transport
          state.transportConnection = transportConnection

          if (recoveryFinished) {
            updateState(cmd, state)
            onPacket(cmd)(GlobalConnectPacket)
          }
      }

      if (recoveryFinished) {
        log.info("Connecting: {}, state: {}", sessionId, state)
      }

    case cmd @ OnFrame(sessionId, payload) =>
      onPayload(cmd)(payload)

    case SendMessage(sessionId,  msg)      =>
      sendMessage(msg)

    case text =>
      println("test: " + text)

  }

  def enableHeartbeat() {
    log.debug("enabled heartbeat, will repeatly send heartbeat every {} seconds", socketio.Settings.heartbeatInterval.seconds)
    heartbeatTask foreach { _.cancel } // it better to confirm previous heartbeatTask was cancled
    heartbeatTask = Some(scheduler.schedule(heartbeatDelay, socketio.Settings.heartbeatInterval.seconds, self, HeartbeatTick))
  }

  private def onPayload(cmd: Command)(payload: ByteString) {
    PacketParser(payload) match {
      case Success(packets)              =>
        packets foreach onPacket(cmd)
      case Failure(ex: ParsingException) => log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
      case Failure(ex)                   => log.warning("Exception during parse socket.io packet: {} ..., due to: {}", payload.take(50).utf8String, ex)
    }
  }

  private def onPacket(cmd: Command)(packet: Packet) {
    packet match {
      case HeartbeatPacket =>
        log.debug("got heartbeat")
        disableCloseTimeout()

      case ConnectPacket(args) =>
        if (recoveryFinished) {
          //TODO
          //publishToNamespace(OnPacket(packet, state.context))
        }
        state.context.isConnected = true
        if (recoveryFinished) {
          sendPacket(packet)
        }

      case DisconnectPacket(endpoint) =>
        if (endpoint == "") {
          if (recoveryFinished) {
            //TODO
            //publishDisconnect(state.context)
          }
          cmd match {
            case _: Closing => // ignore Closing (which is sent from the Transport) to avoid cycle
            case _          => state.transportConnection ! Tcp.Close
          }
          state.transportConnection = context.system.deadLetters
          state.context.isConnected = false
          updateState(cmd, state)

          deactivate()
          enableIdleTimeout()
        }
        else {
          if (recoveryFinished) {
            //TODO
            //publishToNamespace(OnPacket(packet, state.context))
          }
        }
      case _ =>
        packet match {
          case x: DataPacket if x.isAckRequested && !x.hasAckData => sendAck(x, "[]")
          case _ =>
        }
        publishToMediator(OnPacket(packet, state.context))

    }
  }

  def publishToMediator[T <: Packet](msg: OnPacket[T]) {
    mediator ! Publish(topicForClinet, msg, sendOneMessageToEachGroup = false)
  }

  def sendPacket(packets: Packet*) {
    var updatePendingPackets = pendingPackets
    packets foreach { packet => updatePendingPackets = updatePendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
    if (state.context.isConnected) {
      updatePendingPackets = state.context.transport.flushOrWait(state.context, state.transportConnection, updatePendingPackets)
    }
    pendingPackets = updatePendingPackets
  }

  def sendAck(originalPacket: DataPacket, args: String) {
    sendPacket(AckPacket(originalPacket.id, args))
  }

  def enableCloseTimeout() {
    log.debug("enabled close-timeout, will disconnect in {} seconds", socketio.Settings.CloseTimeout)
    closeTimeoutTask foreach { _.cancel } // it better to confirm previous closeTimeoutTask was cancled
    if (context != null) {
      closeTimeoutTask = Some(scheduler.scheduleOnce(socketio.Settings.CloseTimeout.seconds, self, CloseTimeout))
    }
  }

  def enableIdleTimeout() {
    log.debug("enabled idle-timeout, will stop/exit in {} seconds", socketio.Settings.IdleTimeout)
    idleTimeoutTask foreach { _.cancel } // it better to confirm previous idleTimeoutTask was cancled
    if (context != null) {
      idleTimeoutTask = Some(scheduler.scheduleOnce(socketio.Settings.IdleTimeout.seconds, self, IdleTimeout))
    }
  }

  def doStop() {
    deactivate()
    disableIdleTimeout()

    if (SocketIOExtension(context.system).Settings.isCluster) {
      context.parent ! Passivate(stopMessage = PoisonPill)
    } else {
      self ! PoisonPill
    }
  }

  def deactivate() {
    log.debug("deactivated.")
    disableHeartbeat()
    disableCloseTimeout()
  }

  def disableHeartbeat() {
    log.debug("disabled heartbeat")
    heartbeatTask foreach { _.cancel }
    heartbeatTask = None
  }

  def disableCloseTimeout() {
    log.debug("disabled close-timeout")
    closeTimeoutTask foreach { _.cancel }
    closeTimeoutTask = None
  }

  def disableIdleTimeout() {
    log.debug("disabled idle-timeout")
    idleTimeoutTask foreach { _.cancel }
    idleTimeoutTask = None
  }

  def updateState(evt: Any, newState: State) {
    state = newState
  }
  def sendMessage(msg: String) {
    val packet = MessagePacket(-1L, false, msg)
    sendPacket(packet)
  }
}

