package lila.ws

import reactivemongo.api.bson.*
import scala.concurrent.{ ExecutionContext, Future }

import util.RequestHeader

final class Auth(mongo: Mongo, seenAt: SeenAtUpdate)(using executionContext: ExecutionContext):

  import Auth.*
  import Mongo.given

  def apply(req: RequestHeader): Future[Option[UserId]] =
    if (req.flag contains Flag.api) Future successful None
    else
      sessionIdFromReq(req) match
        case Some(sid) if sid startsWith appealPrefix => Future successful None
        case Some(sid) =>
          mongo.security {
            _.find(
              BSONDocument("_id" -> sid, "up" -> true),
              Some(BSONDocument("_id" -> false, "user" -> true))
            ).one[BSONDocument]
          } map {
            _ flatMap {
              _.getAsOpt[UserId]("user")
            }
          } map {
            _ map { user =>
              Impersonations.get(user) getOrElse {
                seenAt(user)
                user
              }
            }
          }
        case None => Future successful None

object Auth:
  private val cookieName     = "lila2"
  private val sessionIdKey   = "sessionId"
  private val sessionIdRegex = s"""$sessionIdKey=(\\w+)""".r.unanchored
  private val sidKey         = "sid"
  private val sidRegex       = s"""$sidKey=(\\w+)""".r.unanchored
  private val appealPrefix   = "appeal:"

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
