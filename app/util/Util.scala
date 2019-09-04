package lila.ws
package util

import play.api.mvc.RequestHeader

object Util {

  def userAgent(req: RequestHeader): String = req.headers.get("User-Agent") getOrElse "?"

  def flagOf(req: RequestHeader): Option[Flag] = req.target getQueryParameter "flag" flatMap Flag.make

  def reqName(req: RequestHeader): String = s"IP: ${req.remoteAddress} UA: ${userAgent(req)}"
}
