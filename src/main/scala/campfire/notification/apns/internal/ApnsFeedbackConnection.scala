package campfire.notification.apns.internal

import javax.net.SocketFactory

/**
 * Created by goldratio on 9/14/14.
 */

object ApnsFeedbackConnection {
  val DELAY_IN_MS = 1000
  val RETRIES = 3

  def apply(factory: SocketFactory, host: String, port: Int, proxy: Option[Proxy] = None,
            readTimeout: Int = 0, connectTimeout: Int = 0, proxyUsername: Option[String] = None,
            proxyPassword: Option[String] = None) = {
    new ApnsFeedbackConnection(factory, host, port, proxy, readTimeout, connectTimeout, proxyUsername, proxyPassword)
  }

}
class ApnsFeedbackConnection(factory: SocketFactory, host: String, port: Int, proxy: Option[Proxy],
                             readTimeout: Int, connectTimeout: Int, proxyUsername: Option[String],
                             proxyPassword: Option[String]) {

}
