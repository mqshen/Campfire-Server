package campfire.socketio

import campfire.socketio.transport.Transport
import spray.http.{HttpOrigin, Uri}

/**
 * Created by goldratio on 9/8/14.
 */
class ConnectionContext(private var _sessionId: String = null, private var _query: Uri.Query = Uri.Query.Empty, private var _origins: Seq[HttpOrigin] = List()) extends Serializable {
  def sessionId = _sessionId
  private[socketio] def sessionId_=(sessionId: String) = {
    _sessionId = sessionId
    this
  }

  def query = _query
  private[socketio] def query_=(query: Uri.Query) = {
    _query = query
    this
  }

  def origins = _origins
  private[socketio] def origins_=(origins: Seq[HttpOrigin]) = {
    _origins = origins
    this
  }

  private var _transport: Transport = campfire.socketio.transport.Empty
  def transport = _transport
  private[socketio] def transport_=(transport: Transport) = {
    _transport = transport
    this
  }

  private var _isConnected: Boolean = _
  def isConnected = _isConnected
  private[socketio] def isConnected_=(isConnected: Boolean) = {
    _isConnected = isConnected
    this
  }

  override def equals(other: Any) = {
    other match {
      case x: ConnectionContext => x.sessionId == this.sessionId && x.query == this.query && x.origins == this.origins
      case _ =>
        false
    }
  }

  override def toString(): String = {
    new StringBuilder().append(sessionId)
      .append(", query=").append(query)
      .append(", origins=").append(origins)
      .append(", isConnected=").append(isConnected)
      .toString
  }
}
