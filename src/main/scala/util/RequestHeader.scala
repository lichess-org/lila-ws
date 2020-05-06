package lila.ws
package util

import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.{ HttpHeaderNames, HttpHeaders, QueryStringDecoder }
import scala.jdk.CollectionConverters._

final class RequestHeader(uri: String, req: HttpHeaders) {

  private val query = new QueryStringDecoder(uri)

  def path = query.path

  def header(name: CharSequence): Option[String] =
    Option(req get name).filter(_.nonEmpty)

  def cookie(name: String): Option[String] =
    for {
      encoded <- header(HttpHeaderNames.COOKIE)
      cookies = ServerCookieDecoder.LAX decode encoded
      cookie <- cookies.asScala.find(_.name contains name)
      value  <- Some(cookie.value).filter(_.nonEmpty)
    } yield value

  def queryParameter(name: String): Option[String] =
    Option(query.parameters.get(name)).map(_ get 0).filter(_.nonEmpty)

  def queryParameterInt(name: String): Option[Int] =
    queryParameter(name) flatMap (_.toIntOption)

  def userAgent: String = header(HttpHeaderNames.USER_AGENT) getOrElse ""

  def origin: Option[String] = header(HttpHeaderNames.ORIGIN)

  def flag: Option[Flag] = queryParameter("flag") flatMap Flag.make

  def ip: Option[IpAddress] = header("X-Forwarded-For") map IpAddress

  def name: String = s"${uri} UA: ${userAgent}"

  def sri = queryParameter("sri") flatMap Sri.from
}
