package campfire.socketio

import akka.actor.{ActorLogging, Actor, Props, ActorRef}

/**
 * Created by goldratio on 9/8/14.
 */
object TransientConnectionActive {
  def props(mediator: ActorRef): Props = Props(classOf[TransientConnectionActive], mediator)
}

final class TransientConnectionActive(val mediator: ActorRef) extends ConnectionActive with Actor with ActorLogging {
  def recoveryFinished: Boolean = true
  def recoveryRunning: Boolean = false

  def receive: Receive = working
}
