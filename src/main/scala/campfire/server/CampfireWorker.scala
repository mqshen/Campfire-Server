package campfire.server

import java.io.{FileInputStream, BufferedInputStream}
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.actor._
import akka.io.Tcp
import akka.util.Timeout
import campfire.database.{User, UserAuth}
import campfire.server.JsonResult._
import campfire.socketio
import campfire.socketio.ConnectionActive
import spray.http.HttpHeaders.{`Set-Cookie`, Cookie}
import campfire.socketio.transport.{Transport, WebSocket}
import spray.can.server.UHttp
import spray.can.{Http, websocket}
import spray.http._
import play.api.libs.json._

import spray.httpx.unmarshalling._

import scala.concurrent.Future

import spray.routing.HttpServiceActor
import spray.can.websocket.frame.{ BinaryFrame, TextFrame }
import spray.can.websocket.FrameCommandFailed

/**
 * Created by goldratio on 9/8/14.
 */


object WebSocketWorker {
  def props(serverConnection: ActorRef) = Props(classOf[WebSocketWorker], serverConnection)
}
final case class Push(msg: String)
class WebSocketWorker(val serverConnection: ActorRef) extends HttpServiceActor with websocket.WebSocketServerWorker {
  override def receive = handshaking orElse businessLogicNoUpgrade orElse closeLogic

  def businessLogic: Receive = {
    // just bounce frames back for Autobahn testsuite
    case x @ (_: BinaryFrame | _: TextFrame) =>
      sender() ! x

    case Push(msg) => send(TextFrame(msg))

    case x: FrameCommandFailed =>
      log.error("frame command failed", x)

    case x: HttpRequest => // do something
  }

  def businessLogicNoUpgrade: Receive = {
    implicit val refFactory: ActorRefFactory = context
    runRoute {
      getFromResourceDirectory("webapp")
    }
  }
}

object CampfireWorker {
  def props(serverConnection: ActorRef, resolver: ActorRef, query: ActorRef) = Props(classOf[CampfireWorker], serverConnection, resolver, query)
}

class CampfireWorker(val serverConnection: ActorRef, resolver: ActorRef, query: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  implicit val timeout = Timeout(120, TimeUnit.SECONDS)

  val WEB_ROOT = "/Users/goldratio/WorkspaceGroup/AkkaWorkspace/Campfire/src/main/webapp"

  var isSocketioHandshaked = false

  var isConnectionActiveClosed = false

  var socketioTransport: Option[Transport] = None

  implicit lazy val soConnContext = new socketio.SoConnectingContext(null, sessionIdGenerator, serverConnection, self, resolver, log, context.dispatcher)

  def readyBehavior = handleSocketioHandshake orElse handleWebsocketConnecting orElse genericLogic orElse handleTerminated

  def upgradedBehavior = handleWebsocket orElse handleTerminated

  def preReceive(msg: Any) {}

  def postReceive(msg: Any) {}

  def ready: Receive = {
    case msg =>
      preReceive(msg)
      readyBehavior.applyOrElse(msg, unhandled)
      postReceive(msg)
  }

  def receive: Receive = ready

  def upgraded: Receive = {
    case msg =>
      preReceive(msg)
      upgradedBehavior.applyOrElse(msg, unhandled)
      postReceive(msg)
  }

  def handleSocketioHandshake: Receive = {
    case socketio.HandshakeRequest(ok) =>
      isSocketioHandshaked = true
      log.debug("socketio connection of {} handshaked.", serverConnection.path)
  }

  def handleWebsocketConnecting: Receive = {
    case req @ websocket.HandshakeRequest(state) =>
      state match {
        case wsFailure: websocket.HandshakeFailure =>
          sender() ! wsFailure.response
        case wsContext: websocket.HandshakeContext =>
          println("test ::" + wsContext)
          val server = UHttp.UpgradeServer(websocket.pipelineStage(self, wsContext), wsContext.response)
          val test = sender()
          println(test)
          println(server)
          sender() ! server
          socketio.wsConnecting(wsContext.request) foreach { _ =>
            socketioTransport = Some(WebSocket)
            log.debug("socketio connection of {} connected on websocket.", serverConnection.path)
          }
      }

    case UHttp.Upgraded =>
      context.become(upgraded)
      log.info("http connection of {} upgraded to websocket, sessionId: {}.", serverConnection.path, soConnContext.sessionId)
  }

  def handleWebsocket: Receive = {
    case frame @ socketio.WsFrame(ok) =>
      log.debug("Got {}", frame)
  }

  def handleTerminated: Receive = {
    case x: Http.ConnectionClosed =>
      closeConnectionActive()
      self ! PoisonPill
      log.debug("http connection of {} stopped due to {}.", serverConnection.path, x)
    case Tcp.Closed => // may be triggered by the first socketio handshake http connection, which will always be droped.
      closeConnectionActive()
      self ! PoisonPill
      log.debug("http connection of {} stopped due to Tcp.Closed}.", serverConnection.path)
  }

  def closeConnectionActive() {
    log.debug("ask to close connectionsActive")
    if (soConnContext.sessionId != null && !isConnectionActiveClosed) {
      isConnectionActiveClosed = true
      //TODO
      //resolver ! ConnectionActive.Closing(soConnContext.sessionId, soConnContext.serverConnection)
    }
  }

  def genericLogic: Receive = {
    case HttpRequest(HttpMethods.POST, Uri.Path("/session"), header, entity, protocol) =>
      val currentSender = sender()
      entity.as[FormData] match {
        case Right(formData) => {
          val userAuth = formData.fields.foldLeft(UserAuth("", "")) {
            case (userAuth, ("login", value: String)) =>
              userAuth.copy(userName = value)
            case (userAuth, ("password", value: String)) =>
              userAuth.copy(password = value)
            case (userAuth, _) =>
              userAuth
          }
          val f = query ? userAuth
          val currentSender = sender()
          f onSuccess {
            case user: User =>
              import campfire.database.UserFormat._
              val content = buildSuccessResult(Json.toJson(user)).toString()
              val entity = HttpEntity(ContentType(MediaTypes.`application/json`), content)
              val sessionId = UUID.randomUUID.toString
              SessionManager.addSession(sessionId, user.userName)
              val sessionCookie = `Set-Cookie`(HttpCookie("session", sessionId))
              currentSender ! HttpResponse(entity = entity, headers = List(sessionCookie))
            case e =>
              println(e)
          }
          f onFailure {
            case t =>
              val result = "An error has occured: " + t.getMessage
              val entity = HttpEntity(ContentType(MediaTypes.`application/json`), result)
              currentSender ! HttpResponse(entity = entity)
          }
        }
        case Left(formData) =>
          val responeEntity = HttpEntity(ContentType(MediaTypes.`application/json`), "")
          currentSender ! HttpResponse(entity = entity)
      }

    case x: HttpRequest =>
      x.headers.foreach { header =>
        println(header.name + ":" + header.value)
      }
      log.info("Got http req uri = {}", x.uri.path.toString.split("/").toList)
      val path = x.uri.path.toString()
      val content = renderFile(WEB_ROOT + path)
      val entity = path match {
        case css: String if css.endsWith(".css") =>
          HttpEntity(ContentType(MediaTypes.`text/css`), content)
        case javascript: String if javascript.endsWith(".js") =>
          HttpEntity(ContentType(MediaTypes.`application/javascript`), content)
        case html: String if html.endsWith(".html") =>
          HttpEntity(ContentType(MediaTypes.`text/html`), content)
        case _ =>
          HttpEntity(ContentType(MediaTypes.`image/png`), content)
      }

      sender() ! HttpResponse(entity = entity)
  }

  def renderFile(path: String) = {
    val bis = new BufferedInputStream(new FileInputStream(path))
    Stream.continually(bis.read).takeWhile(-1 !=).map(_.toByte).toArray
  }

  def sessionIdGenerator: HttpRequest => Future[String] = {
    req => {
      Future(
        getSessionId(req).map { sessionId =>
          if (SessionManager.getUserNameBySession(sessionId).isDefined)
            sessionId
          else {
            println("session not found")
            throw new Exception("The call failed!!")
          }
        }.getOrElse {
          println("session not found")
          throw new Exception("The call failed!!")
        })
    }
  }

  def getSessionId(request: HttpRequest): Option[String] = {
    request.headers.find(
      head => head.isInstanceOf[Cookie]).map { session =>
      session.asInstanceOf[Cookie].cookies.find(cookie => cookie.name == "session")
        .map(session => session.content)
    }.getOrElse(None)
  }
}
