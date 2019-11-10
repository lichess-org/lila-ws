package lila.ws

import javax.inject._
import play.api.mvc.RequestHeader
import reactivemongo.api.bson._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
final class Auth @Inject() (mongo: Mongo, seenAt: SeenAtUpdate)(implicit executionContext: ExecutionContext) {

  import Mongo._

  def apply(req: RequestHeader, flag: Option[Flag]): Future[Option[User]] =
    if (flag contains Flag.api) Future successful None
    else sessionIdFromReq(req) match {
      case Some(sid) =>
        mongo.security {
          _.find(
            BSONDocument("_id" -> sid, "up" -> true),
            Some(BSONDocument("_id" -> false, "user" -> true))
          ).one[BSONDocument]
        } map {
          _ flatMap {
            _.getAsOpt[User.ID]("user") map User.apply
          }
        } map { user =>
          user foreach seenAt.apply
          user
        }
      case None => Future successful None
    }

  private val cookieName = "lila2"
  private val sessionIdKey = "sessionId"
  private val sidRegex = s"""$sessionIdKey=(\\w+)""".r.unanchored

  private def sessionIdFromReq(req: RequestHeader): Option[String] =
    req.cookies.get(cookieName).map(_.value).flatMap {
      case sidRegex(id) => Some(id)
      case _ => None
    } orElse
      req.target.getQueryParameter(sessionIdKey)
}
