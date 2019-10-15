package lila.ws
package util

import java.lang.Double.parseDouble
import java.lang.Integer.parseInt
import java.security.SecureRandom
import play.api.mvc.RequestHeader
import scala.util.Try

object Util {

  def userAgent(req: RequestHeader): String = req.headers.get("User-Agent") getOrElse "?"

  def flagOf(req: RequestHeader): Option[Flag] = req.target getQueryParameter "flag" flatMap Flag.make

  def reqName(req: RequestHeader): String = s"${req.uri} IP: ${req.remoteAddress} UA: ${userAgent(req)}"

  def nowSeconds: Int = (System.currentTimeMillis() / 1000).toInt

  object random {
    private val secureRandom = new SecureRandom()
    private val chars = (('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z')).mkString
    private val nbChars = chars.size
    def char: Char = chars(secureRandom nextInt nbChars)
    def string(len: Int): String = new String(Array.fill(len)(char))
  }

  def parseIntOption(str: String) = Try(parseInt(str)).toOption
  def parseDoubleOption(str: String) = Try(parseDouble(str)).toOption
}
