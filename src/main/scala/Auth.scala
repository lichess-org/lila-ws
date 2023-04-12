package lila.ws

import reactivemongo.api.bson.*
import util.RequestHeader
import com.roundeights.hasher.Algo
import com.typesafe.config.Config

final class Auth(mongo: Mongo, seenAt: SeenAtUpdate, config: Config)(using Executor):

  import Auth.*
  import Mongo.given

  def apply(req: RequestHeader): Future[Option[User.Id]] =
    if (req.flag contains Flag.api) Future successful None
    else
      sessionIdFromReq(req) match
        case Some(sid) if sid startsWith appealPrefix => Future successful None
        case Some(sid)                                => sessionAuth(sid)
        case None =>
          bearerFromHeader(req) match
            case Some(bearer) => bearerAuth(bearer)
            case None         => Future successful None

  private def sessionAuth(sid: String): Future[Option[User.Id]] =
    mongo.security {
      _.find(
        BSONDocument("_id" -> sid, "up" -> true),
        Some(BSONDocument("_id" -> false, "user" -> true))
      ).one[BSONDocument]
    } map {
      _ flatMap { _.getAsOpt[User.Id]("user") }
    } map:
      _ map { user =>
        Impersonations
          .get(user) getOrElse:
            seenAt(user)
            user
      }

  private val bearerSigner = Algo hmac config.getString("oauth.secret")

  private def bearerFromHeader(req: RequestHeader): Option[Auth.Bearer] =
    req.header("Authorization").flatMap { authorization =>
      val prefix = "Bearer "
      if authorization.startsWith(prefix) then
        authorization.stripPrefix(prefix).split(':') match
          case Array(bearer, signed) if bearerSigner.sha1(bearer) hash_= signed => Some(Bearer(bearer))
          case _                                                                => None
      else None
    }

  private def bearerAuth(bearer: Bearer): Future[Option[User.Id]] =
    mongo.oauthColl
      .flatMap {
        _.find(
          BSONDocument("_id" -> AccessTokenId.from(bearer), "scopes" -> "web:socket"),
          Some(BSONDocument("_id" -> false, "userId" -> true))
        ).one[BSONDocument]
      } map:
        _ flatMap { _.getAsOpt[User.Id]("userId") }

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
    req cookie cookieName flatMap:
      case sidRegex(id) => Some(id)
      case _            => None

  opaque type Bearer = String
  object Bearer extends OpaqueString[Bearer]

  opaque type AccessTokenId = String
  object AccessTokenId extends OpaqueString[AccessTokenId]:
    def from(bearer: Bearer) = AccessTokenId(Algo.sha256(bearer.value).hex)
