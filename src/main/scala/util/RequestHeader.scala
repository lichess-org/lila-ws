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

final class RequestHeader(val uri: RequestUri, val ip: IpAddress, val headers: RequestHeader.Headers):

  private val qsd = QueryStringDecoder(uri.value)
  val path = qsd.path

  def cookie(name: String): Option[String] = for
    encoded <- headers.cookie
    cookies = ServerCookieDecoder.LAX.decode(encoded)
    cookie <- cookies.asScala.find(_.name.contains(name))
    value <- Some(cookie.value).filter(_.nonEmpty)
  yield value

  def queryParameter(name: String): Option[String] =
    Option(qsd.parameters.get(name)).map(_.get(0)).filter(_.nonEmpty)

  def queryParameterInt(name: String): Option[Int] =
    queryParameter(name).flatMap(_.toIntOption)

  def isLichobile: Boolean = headers.userAgent.contains("Lichobile/")
  def isLichessMobile: Boolean = headers.userAgent.startsWith("Lichess Mobile/")

  private def lichessMobileSri: Option[String] = headers.userAgent match
    case RequestHeader.lichessMobileSriRegex(sri) => Some(sri)
    case _ => None

  def uncheckedSri: Option[String] = isLichessMobile.so(lichessMobileSri).orElse(queryParameter("sri"))

  def flag: Option[Flag] = queryParameter("flag").flatMap(Flag.make)

  def name: String = s"$uri UA: ${headers.userAgent}"

  override def toString = s"$name origin: ${headers.origin}"

object RequestHeader:

  type Origin = String
  type AuthName = String

  private val lichessMobileSriRegex = """sri:(\S+)""".r.unanchored

  case class Headers(origin: Origin, userAgent: String, cookie: Option[String], authorization: Option[String])

  def makeHeaders(from: HttpHeaders): Headers =
    Headers(
      origin = Option(from.get(HttpHeaderNames.ORIGIN)).orZero,
      userAgent = Option(from.get(HttpHeaderNames.USER_AGENT)).orZero,
      cookie = Option(from.get(HttpHeaderNames.COOKIE)).filter(_.nonEmpty),
      authorization = Option(from.get(HttpHeaderNames.AUTHORIZATION)).filter(_.nonEmpty)
    )
