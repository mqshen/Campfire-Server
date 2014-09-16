package campfire.notification

import java.util.concurrent.TimeUnit

import akka.actor.Actor.Receive
import akka.pattern.ask
import akka.actor.{ Props, ActorSystem, ActorLogging, Actor }
import akka.util.Timeout
import campfire.database.{ UserInfo, User, MongoQuery }
import campfire.notification.apns.ApnsService
import campfire.notification.apns.internal.{ Payload, Utilities, ApnsNotification, ApnsDelegateImpl }
import campfire.server.{ CampfireSslConfiguration, Message }
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * Created by goldratio on 9/14/14.
 */
object NotificationProcessor {
  def props() = Props(classOf[NotificationProcessor])
}

class NotificationProcessor extends Actor with ActorLogging {
  import context.dispatcher
  implicit val timeout = Timeout(120, TimeUnit.SECONDS)

  val config = ConfigFactory.load().getConfig("campfire.apns")
  val Settings = new Settings(config)
  class Settings(config: Config) {
    val host = config.getString("server.host")
    val port = config.getInt("server.port")
  }

  val mongoActor = context.actorOf(MongoQuery.props())

  val delegate = new ApnsDelegateImpl()
  val apns = context.actorOf(ApnsService.props("/Users/goldratio/Documents/ios/Certificates.p12", "miao1qi2",
    Settings.host, Settings.port, delegate))

  override def receive: Receive = {
    case message: Message =>
      val f = mongoActor ? UserInfo(message.toUserName)
      val currentSender = sender()
      f onSuccess {
        case user: User =>
          val payload = Payload(message.content, 1)
          apns ! ApnsNotification(user.deviceToken, payload, 1, 3)
      }
    //    case s : String =>
    //      println(new String(deviceToken))
    //
    //      val payload = Utilities.marshall(0, deviceToken, "{\"aps\":{\"alert\": \"hello for text\", \"badge\": \"1\"}}".getBytes())
    //      apns ! ApnsNotification("" , "" , 1, 3, payload)
  }

}
object Test extends App with CampfireSslConfiguration {

  implicit val timeout = Timeout(120, TimeUnit.SECONDS)
  implicit val system = ActorSystem()
  val test = system.actorOf(NotificationProcessor.props)
  test ! "sss"
}