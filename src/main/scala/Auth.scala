package lila.ws

import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.http.scaladsl.model.HttpRequest
import org.joda.time.DateTime
import reactivemongo.bson._
import scala.concurrent.{ ExecutionContext, Future }

final class Auth(mongo: Mongo, seenAt: SeenAtUpdate)(implicit executionContext: ExecutionContext) {

  import Mongo._

  def apply(sessionId: String): Future[Option[User]] =
    mongo.security {
      _.find(
        BSONDocument("_id" -> sessionId, "up" -> true),
        Some(BSONDocument("_id" -> false, "user" -> true))
      ).one[BSONDocument]
    } map {
      _ flatMap {
        _.getAs[String]("user") map User.apply
      }
    } map { user =>
      user foreach seenAt.apply
      user
    }
}

object Auth {

  private val cookieName = "lila2"
  private val sessionIdKey = "sessionId"
  private val sidRegex = s"""$sessionIdKey=(\\w+)""".r.unanchored

  def sessionIdFromReq(req: HttpRequest): Option[String] =
    req.cookies.collectFirst {
      case c if c.name == cookieName => c.value
    } flatMap {
      case sidRegex(id) => Some(id)
      case _ => None
    } orElse
      req.uri.query().toMap.get(sessionIdKey)
}
