package campfire.socketio

import akka.actor._

/**
 * Created by goldratio on 9/8/14.
 */

object LocalConnectionActiveResolver {
  def props(mediator: ActorRef, connectionActiveProps: Props) = Props(classOf[LocalConnectionActiveResolver], mediator, connectionActiveProps)
}

class LocalConnectionActiveResolver(mediator: ActorRef, connectionActiveProps: Props) extends Actor with ActorLogging {

  def receive = {
    case ConnectionActive.CreateSession(sessionId: String) =>
      context.child(sessionId) match {
        case Some(_) =>
        case None =>
          val connectActive = context.actorOf(connectionActiveProps, name = sessionId)
          context.watch(connectActive)
      }

    case cmd: ConnectionActive.Command =>
      context.child(cmd.sessionId) match {
        case Some(ref) =>
          ref forward cmd
        case None =>
          log.warning("Failed to select actor {}", cmd.sessionId)
      }

    case Terminated(ref) =>

  }
}
