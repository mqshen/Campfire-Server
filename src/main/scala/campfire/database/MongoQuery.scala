package campfire.database

import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue
import play.modules.reactivemongo.json.collection.JSONCollection
import reactivemongo.bson.BSONDocument
import play.api.libs.json._
import akka.actor.{ ActorSystem, Actor, Props, ActorLogging, ActorRef }

import scala.concurrent.Future

case class User(userName: String, nickName: String, avatar: String, password: String = "")

object UserFormat {
  implicit val userFormat = Json.format[User]
}

case class OperationSync(syncKey: Long, syncType: String, operation: String, content: Map[String, String])

object OperationSyncFormat {
  implicit val operationSyncFormat = Json.format[OperationSync]
}

case class UserAuth(userName: String, password: String)
case class UserSync(userName: String, syncKey: Long)

object MongoQuery {
  def props() = Props(classOf[MongoQuery])
}

class MongoQuery extends Actor {
  implicit val userFormat = Json.format[User]
  implicit val operationFormat = Json.format[OperationSync]
  val db = ReactiveMongoPlugin.db

  def receive = {
    case UserAuth(userName, password) =>
      val currentSender = sender()

      import reactivemongo.api._
      import scala.concurrent.ExecutionContext.Implicits.global

      val collection = db.collection[JSONCollection]("user")
      val cursor: Cursor[User] = collection.
        find(Json.obj("userName" -> userName, "password" -> password)).
        cursor[User]

      val futureUsersList: Future[List[User]] = cursor.collect[List]()
      futureUsersList.map { persons =>
        println("use find correct")
        currentSender ! persons(0)
      }
    case UserSync(userName: String, syncKey: Long) =>
      val currentSender = sender()
      import reactivemongo.api._
      import scala.concurrent.ExecutionContext.Implicits.global
      val collection = db.collection[JSONCollection]("user_" + userName)
      val cursor: Cursor[OperationSync] = collection.
        find(Json.obj("syncKey" -> Json.obj("$gt" -> syncKey))).
        cursor[OperationSync]

      val futureUsersList: Future[List[OperationSync]] = cursor.collect[List]()
      //      futureUsersList onComplete {
      //        case t =>
      //          println(t)
      //      }
      futureUsersList.map { operations =>
        println("use find correct")
        currentSender ! operations
      }

  }

}
