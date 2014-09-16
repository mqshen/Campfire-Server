package campfire.notification.apns.internal

import java.io.{ IOException, DataOutputStream, ByteArrayOutputStream, InputStream }
import java.security.KeyStore
import java.util.regex.Pattern
import javax.net.ssl.{ SSLContext, TrustManagerFactory, KeyManagerFactory }

/**
 * Created by goldratio on 9/14/14.
 */
object Utilities {

  def newSSLContext(cert: InputStream, password: String, ksType: String, ksAlgorithm: String): SSLContext = {
    try {
      val ks = KeyStore.getInstance(ksType)
      ks.load(cert, password.toCharArray())
      newSSLContext(ks, password, ksAlgorithm)
    } catch {
      case e: Exception =>
        throw e
    }
  }

  def newSSLContext(ks: KeyStore, password: String, ksAlgorithm: String): SSLContext = {
    try {
      // Get a KeyManager and initialize it
      val kmf = KeyManagerFactory.getInstance(ksAlgorithm)
      kmf.init(ks, password.toCharArray())

      val tmf = TrustManagerFactory.getInstance(ksAlgorithm)
      tmf.init(null.asInstanceOf[KeyStore])

      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null)
      sslContext
    } catch {
      case e: Exception =>
        throw e
    }
  }

  val pattern = Pattern.compile("[ -]")
  def decodeHex(deviceToken: String): Array[Byte] = {
    val hex = pattern.matcher(deviceToken).replaceAll("")

    val bts = new Array[Byte](hex.length / 2)
    (0 until bts.length).foreach { i =>
      bts(i) = (charVal(hex.charAt(2 * i)) * 16 + charVal(hex.charAt(2 * i + 1))).toByte
    }
    bts
  }

  def charVal(a: Char): Int = {
    if ('0' <= a && a <= '9') {
      (a - '0')
    } else if ('a' <= a && a <= 'f') {
      (a - 'a') + 10
    } else if ('A' <= a && a <= 'F') {
      (a - 'A') + 10
    } else {
      throw new RuntimeException("Invalid hex character: " + a)
    }
  }

  def marshall(command: Byte, deviceToken: Array[Byte], payload: Array[Byte]): Array[Byte] = {
    val boas = new ByteArrayOutputStream()
    val dos = new DataOutputStream(boas)

    try {
      dos.writeByte(command)
      dos.writeShort(deviceToken.length)
      dos.write(deviceToken)
      dos.writeShort(payload.length)
      dos.write(payload)
      boas.toByteArray()
    } catch {
      case e: IOException =>
        throw new AssertionError()
    }
  }
}
