package lila.ws
package util

import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.{ HttpHeaderNames, HttpHeaders, QueryStringDecoder }
import scalalib.zeros.given

import scala.jdk.CollectionConverters.*

opaque type RequestUri = String
object RequestUri extends OpaqueString[RequestUri]

opaque type Domain = String
object Domain extends OpaqueString[Domain]

final class RequestHeader(val uri: RequestUri, val ip: IpAddress, headers: HttpHeaders):

  lazy val qsd = QueryStringDecoder(uri.value)
  lazy val path = qsd.path
  lazy val parameters = qsd.parameters

  def header(name: CharSequence): Option[String] =
    Option(headers.get(name)).filter(_.nonEmpty)

  def cookie(name: String): Option[String] = for
    encoded <- header(HttpHeaderNames.COOKIE)
    cookies = ServerCookieDecoder.LAX.decode(encoded)
    cookie <- cookies.asScala.find(_.name.contains(name))
    value <- Some(cookie.value).filter(_.nonEmpty)
  yield value

  def queryParameter(name: String): Option[String] =
    Option(parameters.get(name)).map(_.get(0)).filter(_.nonEmpty)

  def queryParameterInt(name: String): Option[Int] =
    queryParameter(name).flatMap(_.toIntOption)

  def userAgent: String = header(HttpHeaderNames.USER_AGENT).orZero

  def isLichobile: Boolean = userAgent.contains("Lichobile/")
  def isLichessMobile: Boolean = userAgent.startsWith("Lichess Mobile/")

  private def lichessMobileSri: Option[String] = userAgent match
    case RequestHeader.lichessMobileSriRegex(sri) => Some(sri)
    case _ => None

  def uncheckedSri: Option[String] = isLichessMobile.so(lichessMobileSri).orElse(queryParameter("sri"))

  def origin: Option[String] = header(HttpHeaderNames.ORIGIN)

  def flag: Option[Flag] = queryParameter("flag").flatMap(Flag.make)

  def name: String = s"$uri UA: $userAgent"

  def domain = Domain(header(HttpHeaderNames.HOST).getOrElse("?"))

  override def toString = s"$name origin: $origin"

object RequestHeader:

  private val lichessMobileSriRegex = """sri:(\S+)""".r.unanchored
