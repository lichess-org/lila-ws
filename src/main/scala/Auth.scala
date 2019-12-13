package lila.ws

import reactivemongo.api.bson._
import scala.concurrent.{ ExecutionContext, Future }

import util.RequestHeader

final class Auth(mongo: Mongo, seenAt: SeenAtUpdate)(implicit executionContext: ExecutionContext) {

  def apply(req: RequestHeader): Future[Option[User]] =
    if (req.flag contains Flag.api) Future successful None
    else
      sessionIdFromReq(req) match {
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
          } map {
            _ map { user =>
              Impersonations.get(user.id) match {
                case None =>
                  seenAt(user)
                  user
                case Some(impersonatedId) => User(impersonatedId)
              }
            }
          }
        case None => Future successful None
      }

  private val cookieName     = "lila2"
  private val sessionIdKey   = "sessionId"
  private val sessionIdRegex = s"""$sessionIdKey=(\\w+)""".r.unanchored
  private val sidKey         = "sid"
  private val sidRegex       = s"""$sidKey=(\\w+)""".r.unanchored

  def sessionIdFromReq(req: RequestHeader): Option[String] =
    req cookie cookieName flatMap {
      case sessionIdRegex(id) => Some(id)
      case _                  => None
    } orElse
      req.queryParameter(sessionIdKey)

  def sidFromReq(req: RequestHeader): Option[String] =
    req cookie cookieName flatMap {
      case sidRegex(id) => Some(id)
      case _            => None
    }
}
