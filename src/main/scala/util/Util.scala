package lila.ws
package util

import akka.http.scaladsl.model.HttpRequest
import java.security.SecureRandom

object Util {

  def nowSeconds: Int = (System.currentTimeMillis() / 1000).toInt

  def reqName(req: HttpRequest): String = s"${req.uri} IP: ${remoteAddress(req)} UA: ${userAgent(req)}"

  private def header(req: HttpRequest, name: String): Option[String] = req.headers collectFirst {
    case h if h.name == name => h.value
  }

  private def remoteAddress(req: HttpRequest): String = header(req, "Remote-Address") getOrElse "?"

  def userAgent(req: HttpRequest): String = header(req, "User-Agent") getOrElse "?"

  def origin(req: HttpRequest): Option[String] = header(req, "Origin")

  object random {
    private val secureRandom = new SecureRandom()
    private val chars = (('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z')).mkString
    private val nbChars = chars.size
    def char: Char = chars(secureRandom nextInt nbChars)
    def string(len: Int): String = new String(Array.fill(len)(char))
  }
}
