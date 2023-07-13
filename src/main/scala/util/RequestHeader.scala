package lila.ws
package util

import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.{ HttpHeaderNames, HttpHeaders, QueryStringDecoder }
import scala.jdk.CollectionConverters.*
import ornicar.scalalib.zeros.given

opaque type RequestUri = String
object RequestUri extends OpaqueString[RequestUri]

final class RequestHeader(val uri: RequestUri, headers: HttpHeaders):

  def switch(uri: RequestUri): RequestHeader = RequestHeader(uri, headers)

  val (path, parameters) =
    val qsd = QueryStringDecoder(uri.value)
    (qsd.path, qsd.parameters)

  def header(name: CharSequence): Option[String] =
    Option(headers get name).filter(_.nonEmpty)

  def cookie(name: String): Option[String] = for
    encoded <- header(HttpHeaderNames.COOKIE)
    cookies = ServerCookieDecoder.LAX decode encoded
    cookie <- cookies.asScala.find(_.name contains name)
    value  <- Some(cookie.value).filter(_.nonEmpty)
  yield value

  def queryParameter(name: String): Option[String] =
    Option(parameters.get(name)).map(_ get 0).filter(_.nonEmpty)

  def queryParameterInt(name: String): Option[Int] =
    queryParameter(name) flatMap (_.toIntOption)

  def userAgent: String = header(HttpHeaderNames.USER_AGENT) getOrElse ""

  def isLichessMobile: Boolean = userAgent.startsWith("Lichess Mobile/")

  private val lichessMobileSriRegex = """sri:(\S+)""".r.unanchored
  private def lichessMobileSri: Option[String] = userAgent match
    case lichessMobileSriRegex(sri) => Some(sri)
    case _                          => None

  def uncheckedSri: Option[String] = isLichessMobile.so(lichessMobileSri).orElse(queryParameter("sri"))

  def origin: Option[String] = header(HttpHeaderNames.ORIGIN)

  def flag: Option[Flag] = queryParameter("flag") flatMap Flag.make

  def ip: Option[IpAddress] = IpAddress from header("X-Forwarded-For")

  def name: String = s"$uri UA: $userAgent"

  override def toString = s"$name origin: $origin"
