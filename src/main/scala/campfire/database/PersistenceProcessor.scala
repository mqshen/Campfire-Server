package campfire.database

import akka.actor.Actor.Receive
import akka.actor.{ Props, ActorLogging, Actor }
import campfire.server.Message
import play.api.libs.json.Json
import play.modules.reactivemongo.json.collection.JSONCollection

/**
 * Created by goldratio on 9/14/14.
 */
object PersistenceProcessor {
  def props() = Props(classOf[PersistenceProcessor])
}

class PersistenceProcessor extends Actor with ActorLogging {
  import context.dispatcher
  val db = ReactiveMongoPlugin.db

  var syncKey = 0

  override def receive: Receive = {
    case message: Message =>
      import campfire.server.MessageFormat._

      val collection: JSONCollection = db.collection[JSONCollection]("user_" + message.toUserName)
      val json = Json.obj(
        "syncKey" -> syncKey,
        "syncType" -> "message",
        "operation" -> "add",
        "created" -> new java.util.Date().getTime(),
        "content" -> Json.toJson(message))

      collection.insert(json).map(lastError =>
        println("Mongo LastError: %s".format(lastError)))

  }
}
