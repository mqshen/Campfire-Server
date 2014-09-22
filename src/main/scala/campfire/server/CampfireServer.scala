package campfire.server

import java.text.MessageFormat
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.io.IO
import akka.util.Timeout
import campfire.notification.NotificationProcessor
import campfire.server.JsonResult._
import campfire.server.RoomServerActor.CreateRoom
import campfire.server.ServerExtension.{ Subscribe, OnData, OnEvent }
import campfire.socketio.ConnectionActive.SendMessage
import play.api.libs.json.Json
import rx.lang.scala.{ Observable, Subject, Observer }
import spray.can.Http
import campfire.database.{ OperationSync, UserSync, ReactiveMongoPlugin, MongoQuery }
import spray.can.server.UHttp

/**
 * Created by goldratio on 9/8/14.
 */
object CampfireServer {
  def props(resovler: ActorRef, query: ActorRef) = Props(classOf[CampfireServer], resovler, query)
}

case class Message(fromUserName: String, toUserName: String, timestamp: Long, `type`: Int, content: String, clientMsgId: Long)
case class MessageEvent(name: String, content: Message)

object MessageFormat {
  implicit val messageFormat = Json.format[Message]
  implicit val messageeventFormat = Json.format[MessageEvent]
}

class CampfireServer(resovler: ActorRef, query: ActorRef) extends Actor with ActorLogging {
  def receive = {
    // when a new connection comes in we register a SocketIOConnection actor as the per connection handler
    case Http.Connected(remoteAddress, localAddress) =>
      val serverConnection = sender()
      println(serverConnection)
      val conn = context.actorOf(CampfireWorker.props(serverConnection, resovler, query))
      serverConnection ! Http.Register(conn)
  }
}

object Main extends App with CampfireSslConfiguration {

  implicit val timeout = Timeout(120, TimeUnit.SECONDS)
  implicit val system = ActorSystem()
  val serverExt = ServerExtension(system)
  implicit val resolver = serverExt.resolver
  val roomResolver = serverExt.roomResolver
  import system.dispatcher

  ReactiveMongoPlugin.start(system)

  val mongoActor = system.actorOf(MongoQuery.props())
  val notificationProcessor = system.actorOf(NotificationProcessor.props())

  val observer = new Observer[OnEvent] {
    override def onNext(value: OnEvent) {
      value match {
        case event @ OnEvent("chat", args, context) =>

          try {
            import MessageFormat._
            val packets = Json.parse(args).as[List[Message]]
            packets.foreach { packet =>
              if (event.packet.hasAckData) {
                event.ack((System.currentTimeMillis() - packet.timestamp).toString)
              }
              SessionManager.getSessionIdByName(packet.toUserName).map { sessionId =>
                val messageEvent = MessageEvent("chat", packet)
                resolver ! SendMessage(sessionId, Json.toJson(messageEvent).toString())
              }
                .getOrElse {
                  notificationProcessor ! packet
                }
            }
          } catch {
            case e: Exception =>
              e.printStackTrace()
          }
        case event @ OnEvent("sync", args, context) =>
          try {
            SessionManager.getUserNameBySession(event.sessionId).map { userName =>
              val syncKeys = Json.parse(args).as[List[Long]]
              syncKeys.foreach { syncKey =>
                val userSync = UserSync(userName, syncKey)
                val f = mongoActor ? userSync
                f onSuccess {
                  case operations: List[OperationSync] =>
                    import campfire.database.OperationSyncFormat._
                    val content = buildSyncResult(Json.toJson(operations)).toString()
                    resolver ! SendMessage(event.sessionId, content)
                }
                f onFailure {
                  case t =>
                }
              }
            }
          }
          catch {
            case e: Exception =>
              e.printStackTrace()
          }
        case event @ OnEvent("create", args, context) =>
          try {
            val users = Json.parse(args).as[List[String]]
            roomResolver ! CreateRoom(users)
          }
          catch {
            case e: Exception =>
              e.printStackTrace()
          }
        case event @ OnEvent("chatRoom", args, context) =>
          try {
            import MessageFormat._
            val packets = Json.parse(args).as[List[Message]]
            packets.foreach { packet =>
              roomResolver ! packet
            }
          }
          catch {
            case e: Exception =>
              e.printStackTrace()
          }
        case _ =>
          println("observed: " + value)
      }
    }
  }

  val channel = Subject[OnData]()
  // there is no channel.ofType method for RxScala, why?
  channel.flatMap {
    case x: OnEvent => Observable.items(x)
    case _          => Observable.empty
  }.subscribe(observer)

  serverExt.serverActor ! Subscribe(channel)
  val server = system.actorOf(CampfireServer.props(resolver, mongoActor), name = "campfire-server")

  IO(UHttp) ! Http.Bind(server, interface = "0.0.0.0", port = 8080)

}
