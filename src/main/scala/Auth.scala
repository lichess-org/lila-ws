package lila.ws

import reactivemongo.api.bson.*
import util.RequestHeader
import com.roundeights.hasher.Algo
import com.typesafe.config.Config

final class Auth(mongo: Mongo, seenAt: SeenAtUpdate, config: Config)(using Executor):

  import Auth.*
  import Mongo.given

  def apply(req: RequestHeader): Future[Option[Success]] =
    if req.flag contains Flag.api then Future successful None
    else
      sessionIdFromReq(req) match
        case Some(sid) if sid startsWith appealPrefix => Future successful None
        case Some(sid)                                => sessionAuth(sid)
        case None =>
          bearerFromHeader(req) match
            case Some(bearer) => bearerAuth(bearer)
            case None         => Future successful None

  def sidFromReq(req: RequestHeader): Option[String] =
    req cookie cookieName flatMap:
      case sidRegex(id) => Some(id)
      case _            => None

  private def sessionAuth(sid: String): Future[Option[Success.Cookie]] =
    mongo
      .security:
        _.find(
          BSONDocument("_id" -> sid, "up" -> true),
          Some(BSONDocument("_id" -> false, "user" -> true))
        ).one[BSONDocument]
      .map:
        _ flatMap { _.getAsOpt[User.Id]("user") }
      .map:
        _.map: user =>
          Success.Cookie:
            Impersonations
              .get(user)
              .getOrElse:
                seenAt(user)
                user

  private val cookieName = config.getString("cookie.name")

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

  private def bearerAuth(bearer: Bearer): Future[Option[Success]] =
    mongo.oauthColl
      .flatMap:
        _.find(
          BSONDocument("_id" -> AccessTokenId.from(bearer), "scopes" -> "web:mobile"),
          Some(BSONDocument("_id" -> false, "userId" -> true))
        ).one[BSONDocument]
      .map:
        _.flatMap:
          _.getAsOpt[User.Id]("userId").map(Success.OAuth(_))

  private def sessionIdFromReq(req: RequestHeader): Option[String] =
    req cookie cookieName flatMap {
      case sessionIdRegex(id) => Some(id)
      case _                  => None
    } orElse
      req.queryParameter(sessionIdKey)

object Auth:
  private val sessionIdKey   = "sessionId"
  private val sessionIdRegex = s"""$sessionIdKey=(\\w+)""".r.unanchored
  private val sidKey         = "sid"
  private val sidRegex       = s"""$sidKey=(\\w+)""".r.unanchored
  private val appealPrefix   = "appeal:"

  enum Success(val user: User.Id):
    case Cookie(u: User.Id) extends Success(u)
    case OAuth(u: User.Id)  extends Success(u)

  opaque type Bearer = String
  object Bearer extends OpaqueString[Bearer]

  opaque type AccessTokenId = String
  object AccessTokenId extends OpaqueString[AccessTokenId]:
    def from(bearer: Bearer) = AccessTokenId(Algo.sha256(bearer.value).hex)
