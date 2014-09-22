package campfire.socketio.benchmark

/**
 * Created by goldratio on 9/16/14.
 */

import campfire.socketio.benchmark.SocketIOTestClient.MessageArrived

import scala.util.{Success, Failure}
import akka.actor._
import akka.dispatch.sysmsg.Failed
import akka.io.IO
import akka.pattern.ask
import campfire.database.UserAuth
import campfire.server.{Message, MessageFormat}
import campfire.socketio.packet.{MessagePacket, EventPacket, Packet}
import com.typesafe.config.ConfigFactory
import play.api.libs.json.Json
import spray.can.Http
import spray.can.server.UHttp
import spray.can.websocket
import spray.can.websocket.frame.TextFrame
import campfire.socketio
import spray.http._
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import spray.client.pipelining._

import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.tools.nsc.interactive.Response

import spray.util._

object SocketIOTestClient {
  implicit val system = ActorSystem()
  import system.dispatcher

  private var _nextId = 0
  private def nextId = {
    _nextId += 1
    _nextId
  }

  val wsHandshakeReq = websocket.basicHandshakeRepuset("/mytest")

  case object OnOpen
  case object OnClose
  case class MessageArrived(roundtrip: Long)

  case object SendTimestampedChat
  case object SendHello
  case class SendBroadcast(msg: String)

  case class User(userNa: Int)

  def main (args: Array[String]) {
    val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
    val clientConfig = config.getConfig("client")


    val addresses = clientConfig.getStringList("addresses")
    val connect = addresses map (
      _ split (":") match {
        case Array(host: String, port: String) => (s"http://${host}:${port}/session",Http.Connect(host, port.toInt))
      })


    val connectAddress = connect(ThreadLocalRandom.current.nextInt(connect.size))
    val uri = Uri(connectAddress._1)
    val pipeline = sendReceive
    val responseFuture: Future[HttpResponse] = pipeline(Post(uri, FormData(Seq("userName"->"goldratio", "password"->"111111"))))
    responseFuture onComplete {
      case Success(response) =>
        val sessionId = response.headers.foldLeft("") {
          case (sessionId, HttpHeader("set-cookie", session)) =>
            session.substring(8)
          case head =>
            head._1
        }
        system.actorOf(Props(new SocketIOTestClientTest(sessionId, connectAddress._2)))
        shutdown()
      case Failure(error) =>
        shutdown()
    }
  }

  def shutdown(): Unit = {
    //IO(Http).ask(Http.CloseAll)(1.second).await
    //system.shutdown()
  }
}


class SocketIOTestClientTest(sessionId: String,connect : Http.Connect) extends Actor with ActorLogging {

  val client = context.actorOf(Props(new SocketIOTestClient(connect, sessionId, self)))

  override def receive: Actor.Receive = {
    case MessageArrived(roundtripTime: Long) =>
      client ! SocketIOTestClient.SendTimestampedChat
    case e =>
      client ! SocketIOTestClient.SendTimestampedChat
  }
}

class SocketIOTestClient(connect: Http.Connect, val sessionId: String, commander: ActorRef) extends Actor with socketio.SocketIOClientWorker {
  import SocketIOTestClient._

  val Id = nextId.toString

  import context.system
  try {
    IO(UHttp)(ActorSystem("websocket")) ! connect
  }
  catch {
    case e:Exception =>
      e.printStackTrace()
  }

  def businessLogic: Receive = {
    case SendHello           =>
      connection ! TextFrame("5:::{\"name\":\"hello\", \"args\":[]}")
    case SendTimestampedChat =>
      connection ! TextFrame(timestampedChat)
    case SendBroadcast(msg)  =>
      connection ! TextFrame("""5:::{"name":"broadcast", "args":[""" + "\"" + msg + "\"" + "]}")
//    case test:TextFrame =>
//      println(test.payload)
  }

  override def onDisconnected(args: Seq[(String, String)]) {
    commander ! OnClose
  }

  override def onOpen() {
    commander ! OnOpen
  }

  def onPacket(packet: Packet) {
    val messageArrivedAt = System.currentTimeMillis
    packet match {
      case EventPacket("chat", args) =>
        import MessageFormat._
        val packets = Json.parse(args).as[List[Message]]
        packets.foreach { packet =>
          val roundtripTime = messageArrivedAt - packet.timestamp.toLong
          log.debug("roundtripTime {}", roundtripTime)
          commander ! MessageArrived(roundtripTime)
        }
      case msg: MessagePacket => commander ! msg.data
      case _                  =>
    }
  }

  override def onAck(id: Long, args: String): Unit = {
    commander ! MessageArrived(args.toLong)
  }

  def chat(message: String): String = {
    "5:" + Id + "+:{\"name\":\"chat\",\"args\":[{\"fromUserName\":\"mqshen\",\"timestamp\":" + message + ",\"toUserName\":\"mqshen\",\"content\":\"23\",\"type\":1,\"clientMsgId\":123123}]}"
  }

  def timestampedChat = {
    val message = System.currentTimeMillis.toString
    chat(message)
  }

}
