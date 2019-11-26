package lila.ws
package util

import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.{ HttpHeaders, QueryStringDecoder }
import scala.jdk.CollectionConverters._

final class RequestHeader(uri: String, req: HttpHeaders, val ip: IpAddress) {

  private val query = new QueryStringDecoder(uri)

  def path = query.path

  def header(name: String): Option[String] =
    Option(req get name).filter(_.nonEmpty)

  def cookie(name: String): Option[String] = for {
    encoded <- header(HttpHeaders.Names.COOKIE)
    cookies = ServerCookieDecoder.LAX decode encoded
    cookie <- cookies.asScala.find(_.name contains name)
    value <- Some(cookie.value).filter(_.nonEmpty)
  } yield value

  def queryParameter(name: String): Option[String] =
    Option(query.parameters.get(name)).map(_ get 0).filter(_.nonEmpty)

  def queryParameterInt(name: String): Option[Int] =
    queryParameter(name) flatMap (_.toIntOption)

  def userAgent: String = header(HttpHeaders.Names.USER_AGENT) getOrElse ""

  def origin: Option[String] = header(HttpHeaders.Names.ORIGIN)

  def flag: Option[Flag] = queryParameter("flag") flatMap Flag.make

  def name: String = s"${uri} UA: ${userAgent}"

  def sri = queryParameter("sri") flatMap Sri.from
}
