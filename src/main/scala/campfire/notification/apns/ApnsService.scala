package campfire.notification.apns

import java.io.FileInputStream

import akka.actor.Actor.Receive
import campfire.notification.apns.internal.ApnsConnection.Connect
import campfire.notification.apns.internal.Utilities._
import campfire.notification.apns.internal._

import akka.actor._
import akka.pattern.ask
/**
 * Created by goldratio on 9/14/14.
 */

object ApnsService
{
  val KEYSTORE_TYPE = "PKCS12"
  val KEY_ALGORITHM = if (java.security.Security.getProperty("ssl.KeyManagerFactory.algorithm") == null) "sunx509" else
    java.security.Security.getProperty("ssl.KeyManagerFactory.algorithm")



  def props( fileName: String, password: String, host: String, port: Int, delegate: ApnsDelegate,
             proxy : Option[Proxy] = None, readTimeout: Int = 0, connectTimeout: Int = 0,
             proxyUsername: Option[String] = None, proxyPassword: Option[String] = None ) =
    Props(classOf[ApnsService], fileName, password, host, port, delegate, proxy,
      readTimeout, connectTimeout, proxyUsername, proxyPassword)

}
class ApnsService(
                   fileName: String, password: String, host: String, port: Int, delegate: ApnsDelegate,
                  proxy : Option[Proxy] = None, readTimeout: Int = 0, connectTimeout: Int = 0,
                  proxyUsername: Option[String] = None, proxyPassword: Option[String] = None
                   ) extends Actor {

  import ApnsService._

  val conn = {
    val stream = new FileInputStream(fileName)
    val sslContext = newSSLContext(stream, password, KEYSTORE_TYPE, KEY_ALGORITHM)
    val sslFactory = sslContext.getSocketFactory()

    val feedback = ApnsFeedbackConnection(sslFactory, host, port, proxy, readTimeout, connectTimeout,
      proxyUsername, proxyPassword)

    context.actorOf(ApnsConnection.props(sslFactory, host, port, proxy, proxyUsername, proxyPassword,
      EVERY_NOTIFICATION, delegate, readTimeout = readTimeout, connectTimeout = connectTimeout))
  }

  conn ! Connect

  override def receive: Receive = {
    case msg: ApnsNotification =>
      conn ! msg
  }

}
