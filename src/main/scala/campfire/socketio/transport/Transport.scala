package campfire.socketio.transport

import akka.actor.ActorRef
import akka.util.ByteString
import campfire.socketio.ConnectionContext
import campfire.socketio.packet.{ Packet, NoopPacket, HeartbeatPacket }
import spray.can.websocket.FrameCommand
import spray.can.websocket.frame.TextFrame

import scala.collection.immutable

/**
 * Created by goldratio on 9/8/14.
 */
object Transport {
  val transportIds = Set(
    //    XhrPolling,
    //    XhrMultipart,
    //    HtmlFile,
    WebSocket,
    //    FlashSocket,
    //    JsonpPolling,
    Empty).map(x => x.ID -> x).toMap

  def isSupported(id: String) = transportIds.contains(id) && id != Empty.ID
}

trait Transport extends Serializable {
  def ID: String

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet]

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String)

  /**
   * It seems XHR-Pollong client does not support multile packets.
   */
  protected[socketio] def writeMultiple(connContext: ConnectionContext, transportConnection: ActorRef, _pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    var pendingPackets = _pendingPackets
    if (pendingPackets.isEmpty) {
      // do nothing
    } else if (pendingPackets.tail.isEmpty) {
      val head = pendingPackets.head
      pendingPackets = pendingPackets.tail
      val payload = head.render.utf8String
      write(connContext, transportConnection, payload)
    } else {
      var totalLength = 0
      val sb = new StringBuilder()
      var prev: Packet = null
      while (pendingPackets.nonEmpty) {
        val curr = pendingPackets.head
        curr match {
          case NoopPacket | HeartbeatPacket if curr == prev => // keep one is enough
          case _ =>
            val msg = curr.render.utf8String
            totalLength += msg.length
            sb.append('\ufffd').append(msg.length.toString).append('\ufffd').append(msg)
        }
        pendingPackets = pendingPackets.tail
        prev = curr
      }
      val payload = sb.toString
      write(connContext, transportConnection, payload)
    }

    pendingPackets
  }

  protected[socketio] def writeSingle(connContext: ConnectionContext, transportConnection: ActorRef, isSendingNoopWhenEmpty: Boolean, _pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    var pendingPackets = _pendingPackets
    if (pendingPackets.isEmpty) {
      if (isSendingNoopWhenEmpty) {
        write(connContext, transportConnection, NoopPacket.utf8String)
      }
    } else {
      val head = pendingPackets.head
      pendingPackets = pendingPackets.tail
      val payload = head.render.utf8String
      //println("Write {}, to {}", payload, transportConnection)
      write(connContext, transportConnection, payload)
    }
    pendingPackets
  }

}

object WebSocket extends Transport {
  val ID = "websocket"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    writeMultiple(connContext, transportConnection, pendingPackets)
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
    transportConnection ! FrameCommand(TextFrame(ByteString(payload)))
  }
}

object Empty extends Transport {
  val ID = "empty"

  protected[socketio] def flushOrWait(connContext: ConnectionContext, transportConnection: ActorRef, pendingPackets: immutable.Queue[Packet]): immutable.Queue[Packet] = {
    pendingPackets
  }

  protected[socketio] def write(connContext: ConnectionContext, transportConnection: ActorRef, payload: String) {
  }
}