package campfire.server

import play.api.libs.json.JsValue
import play.api.libs.json.Json

/**
 * Created by goldratio on 9/8/14.
 */
object JsonResult {

  def buildSuccessResult(content: JsValue): JsValue = {
    Json.obj("ret" -> 0, "errMsg" -> "", "content" -> content)
  }

  def buildSyncResult(content: JsValue): JsValue = {
    Json.obj("name" -> "sync", "content" -> content)
  }
}
