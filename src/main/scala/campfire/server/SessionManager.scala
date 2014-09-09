package campfire.server

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.collection.mutable

/**
 * Created by goldratio on 9/8/14.
 */
object SessionManager {

  val sessionUserMap: mutable.Map[String, String] = new ConcurrentHashMap[String, String]().asScala
  val userSessionMap: mutable.Map[String, String] = new ConcurrentHashMap[String, String]().asScala

  def addSession(sessionId: String, userName: String) = {
    sessionUserMap += (sessionId -> userName)
    userSessionMap += (userName -> sessionId)
  }

  def getSessionIdByName(userName: String): Option[String] = {
    userSessionMap.get(userName)
  }

  def getUserNameBySession(sessionId: String): Option[String] = {
    sessionUserMap.get(sessionId)
  }

  def removeBySessionId(sessionId: String) {
    sessionUserMap.remove(sessionId).map { userName =>
      userSessionMap.remove(userName)
    }
  }
}
