package campfire.socketio

import java.util.concurrent.{ TimeUnit, ThreadFactory }

import akka.actor._
import akka.dispatch.MonitorableThreadFactory
import akka.event.{ Logging, LoggingAdapter }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Created by goldratio on 9/8/14.
 */
object SocketIOExtension extends ExtensionId[SocketIOExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): SocketIOExtension = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = SocketIOExtension

  override def createExtension(system: ExtendedActorSystem): SocketIOExtension = new SocketIOExtension(system)

  val mediatorName: String = "socketioMediator"
  val mediatorSingleton: String = "active"

}
class SocketIOExtension(system: ExtendedActorSystem) extends Extension {
  private val log = Logging(system, "SocketIO")

  private[socketio] object Settings {
    val config = system.settings.config.getConfig("spray.socketio")
    val isCluster: Boolean = config.getString("mode") == "cluster"
    val connRole: String = "connectionActive"
    val enableConnPersistence: Boolean = config.getBoolean("server.enable-connectionactive-persistence")
    val schedulerTickDuration: FiniteDuration = Duration(config.getDuration("scheduler.tick-duration", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    val schedulerTicksPerWheel: Int = config.getInt("scheduler.ticks-per-wheel")
  }

  lazy val localMediator = system.actorOf(LocalMediator.props(), name = SocketIOExtension.mediatorName)

  lazy val connectionActiveProps: Props = TransientConnectionActive.props(localMediator)

  lazy val resolver =
    system.actorOf(LocalConnectionActiveResolver.props(localMediator, connectionActiveProps), name = ConnectionActive.shardName)

  lazy val scheduler: Scheduler = {
    import scala.collection.JavaConverters._
    log.info("Using a dedicated scheduler for socketio with 'spray.socketio.scheduler.tick-duration' [{} ms].", Settings.schedulerTickDuration.toMillis)

    val cfg = ConfigFactory.parseString(
      s"socketio.scheduler.tick-duration=${Settings.schedulerTickDuration.toMillis}ms").withFallback(
        system.settings.config)
    val threadFactory = system.threadFactory match {
      case tf: MonitorableThreadFactory => tf.withName(tf.name + "-socketio-scheduler")
      case tf                           => tf
    }
    system.dynamicAccess.createInstanceFor[Scheduler](system.settings.SchedulerClass, immutable.Seq(
      classOf[Config] -> cfg,
      classOf[LoggingAdapter] -> log,
      classOf[ThreadFactory] -> threadFactory)).get
  }
}
