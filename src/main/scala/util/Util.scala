package lila.ws
package util

import akka.http.scaladsl.model.HttpRequest

object Util {

  def nowSeconds: Int = (System.currentTimeMillis() / 1000).toInt

  // def flagOf(req: HttpRequest): Option[Flag] = req.target getQueryParameter "flag" flatMap Flag.make

  def reqName(req: HttpRequest): String = s"IP: ${remoteAddress(req)} UA: ${userAgent(req)}"

  private def header(req: HttpRequest, name: String): Option[String] = req.headers collectFirst {
    case h if h.name == name => h.value
  }

  private def remoteAddress(req: HttpRequest): String = header(req, "Remote-Address") getOrElse "?"

  private def userAgent(req: HttpRequest): String = header(req, "User-Agent") getOrElse "?"
}
