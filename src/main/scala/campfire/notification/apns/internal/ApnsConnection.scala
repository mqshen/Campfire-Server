package campfire.notification.apns.internal

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import java.net.Socket
import javax.net.SocketFactory

import akka.actor.{Props, ActorLogging, Actor}
import play.api.libs.json.Json

/**
 * Created by goldratio on 9/14/14.
 */

sealed trait ReconnectPolicy {
  def name: String
  def reconnected(): Unit = {

  }
}
case object NEVER extends ReconnectPolicy { val name = "NEVER" }
case object EVERY_HALF_HOUR extends ReconnectPolicy { val name = "EVERY_HALF_HOUR" }
case object EVERY_NOTIFICATION extends ReconnectPolicy { val name = "EVERY_NOTIFICATION" }

//TODO alert can be map
case class Payload(alert: String, badge: Int, sound: String = "", `content-available`: Int = 0)
case class ApnsNotification(deviceToken: String, aps: Payload, identifier: Int, expiry: Int)

trait ApnsDelegate
{
  def messageSent(message: ApnsNotification)
}

class ApnsDelegateImpl extends ApnsDelegate
{
  override def messageSent(message: ApnsNotification): Unit = {
    println("message send" + message.deviceToken)
  }
}


object ApnsConnection
{
  val threadId = new AtomicInteger(0)

  case object Connect

//  def apply(
//             factory: SocketFactory, host: String, port: Int, proxy: Option[Proxy],
//            proxyUsername: Option[String] , proxyPassword: Option[String], reconnectPolicy: ReconnectPolicy ,
//            delegate: ApnsDelegate , errorDetection: Boolean = true, tf: Option[ThreadFactory] = None ,
//            cacheLength: Int = 0, autoAdjustCacheLength: Boolean = false, readTimeout: Int, connectTimeout: Int
//             ): ApnsConnection = {
//    new ApnsConnection(factory, host, port, proxy, proxyUsername,
//      proxyPassword, reconnectPolicy, delegate, errorDetection, tf, cacheLength,
//      autoAdjustCacheLength, readTimeout, connectTimeout)
//  }
  def props( factory: SocketFactory, host: String, port: Int, proxy: Option[Proxy],
             proxyUsername: Option[String] , proxyPassword: Option[String], reconnectPolicy: ReconnectPolicy ,
             delegate: ApnsDelegate , errorDetection: Boolean = true, tf: Option[ThreadFactory] = None ,
             cacheLength: Int = 0, autoAdjustCacheLength: Boolean = false, readTimeout: Int, connectTimeout: Int ) =
    Props(classOf[ApnsConnection], factory, host, port, proxy, proxyUsername,
      proxyPassword, reconnectPolicy, delegate, errorDetection, tf, cacheLength,
      autoAdjustCacheLength, readTimeout, connectTimeout)
}

class ApnsConnection(factory: SocketFactory, host: String, port: Int, proxy: Option[Proxy],
                     proxyUsername: Option[String], proxyPassword: Option[String], reconnectPolicy: ReconnectPolicy ,
                     delegate: ApnsDelegate , errorDetection: Boolean, tf: Option[ThreadFactory] , cacheLength: Int,
                     autoAdjustCacheLength: Boolean, readTimeout: Int, connectTimeout: Int) extends Actor with ActorLogging{

  import ApnsConnection._
  val threadFactory = tf.getOrElse(defaultThreadFactory())

  var socket: Option[Socket] = None

  def defaultThreadFactory() {
    new ThreadFactory() {
      val wrapped = Executors.defaultThreadFactory()

      override def newThread(r: Runnable): Thread = {
        val result = wrapped.newThread(r)
        result.setName("MonitoringThread-" + threadId.incrementAndGet())
        result.setDaemon(true)
        result
      }
    }
  }

  def connect: Receive = {
    case Connect =>
      socket = Some(factory.createSocket(host, port))
      socket.map { s =>
        s.setSoTimeout(readTimeout)
        s.setKeepAlive(true)
        reconnectPolicy.reconnected()
        context.become(process)
      }
  }

  def process: Receive = {
    case notification: ApnsNotification =>
      socket.map { s =>
        implicit val payloadFormat = Json.format[Payload]
        val aps = Json.obj("aps" -> Json.toJson(notification.aps)).toString().getBytes()
        val payload = Utilities.marshall(0, Utilities.decodeHex(notification.deviceToken), aps)

        try {
          s.getOutputStream().write(payload)
          s.getOutputStream().flush()
          delegate.messageSent(notification)
        }
        catch {
          case e: Exception =>
            e.printStackTrace()
        }
      }
  }

  override def receive: Actor.Receive = connect orElse process
}
