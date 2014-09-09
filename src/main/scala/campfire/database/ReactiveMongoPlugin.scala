package campfire.database

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import reactivemongo.api._
import reactivemongo.core.commands._
import reactivemongo.core.nodeset.Authenticate
import scala.concurrent.{ Await, ExecutionContext }
import scala.util.{ Failure, Success }

/**
 * Created by goldratio on 8/27/14.
 */
class ReactiveMongoPlugin {
  private var _helper: Option[ReactiveMongoHelper] = None
  def helper: ReactiveMongoHelper = _helper.getOrElse(throw new RuntimeException("ReactiveMongoPlugin error: no ReactiveMongoHelper available?"))

  def onStart(actorSystem: ActorSystem) {
    _helper = {
      val config = ConfigFactory.load().getConfig("mongodb")
      val conf = ReactiveMongoPlugin.parseConf(config)
      try {
        Some(ReactiveMongoHelper(conf._1, conf._2, conf._3, conf._4, actorSystem))
      } catch {
        case e: Throwable => {
          throw e
          //throw new Exception("ReactiveMongoPlugin Initialization Error", "An exception occurred while initializing the ReactiveMongoPlugin.", e)
        }
      }
    }
  }

  def onStop {
    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global
    //Logger.info("ReactiveMongoPlugin stops, closing connections...")
    _helper.map { h =>
      println()
    }
  }

}

object ReactiveMongoPlugin {

  var current: Option[ReactiveMongoPlugin] = None

  def start(actorSystem: ActorSystem) {
    current match {
      case None =>
        current = {
          val plugin = new ReactiveMongoPlugin()
          plugin.onStart(actorSystem)
          Some(plugin)
        }
      case _ =>
        println("already start")
    }
  }

  def driver = current.get.helper.driver
  def db = current.get.helper.db
  def connection = current.get.helper.connection

  def parseConf(conf: Config): (String, List[String], List[Authenticate], Option[Int]) = {
    val uri = conf.getString("uri")
    val (dbName, servers, auth) = MongoConnection.parseURI(uri) match {
      case Success(MongoConnection.ParsedURI(hosts, Some(db), auth)) =>
        (db, hosts.map(h => h._1 + ":" + h._2), auth.toList)
      case Success(MongoConnection.ParsedURI(_, None, _)) =>
        throw new Exception("Missing database name in mongodb.uri '$uri'")
      case Failure(e) => throw new Exception("Invalid mongodb.uri '$uri'")
    }

    val nbChannelsPerNode = if (conf.hasPath("channels")) Some(conf.getInt("channels")) else None
    (dbName, servers, auth, nbChannelsPerNode)
  }
}

case class ReactiveMongoHelper(dbName: String, servers: List[String],
                               auth: List[Authenticate], nbChannelsPerNode: Option[Int], actorSystem: ActorSystem) {

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  lazy val driver = new MongoDriver(actorSystem)
  lazy val connection = nbChannelsPerNode match {
    case Some(numberOfChannels) =>
      driver.connection(servers, auth, nbChannelsPerNode = numberOfChannels)
    case _ =>
      driver.connection(servers, auth)
  }
  lazy val db = DB(dbName, connection)
}
