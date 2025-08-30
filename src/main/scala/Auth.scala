package lila.ws

import com.roundeights.hasher.Algo
import com.typesafe.config.Config
import reactivemongo.api.bson.*

import scala.jdk.CollectionConverters.given

import util.RequestHeader

final class Auth(mongo: Mongo, seenAt: SeenAtUpdate, config: Config)(using Executor):

  import Auth.*
  import Mongo.given

  def apply(req: RequestHeader): Future[Option[Success]] =
    if req.flag.exists(flag => flag == Flag.api || flag == Flag.embed)
    then Future.successful(None)
    else
      sessionIdFromReq(req) match
        case Some(sid) if sid.startsWith(appealPrefix) => Future.successful(None)
        case Some(sid) => sessionAuth(sid)
        case None =>
          bearerFromHeader(req) match
            case Some(bearer) => bearerAuth(bearer)
            case None => Future.successful(None)

  def sidFromReq(req: RequestHeader): Option[String] =
    req
      .cookie(cookieName)
      .flatMap:
        case sidRegex(id) => Some(id)
        case _ => None

  private def sessionAuth(sid: String): Future[Option[Success.Cookie]] =
    mongo
      .security:
        _.find(BSONDocument("_id" -> sid, "up" -> true), sessionAuthDbProj).one[BSONDocument]
      .map:
        _.flatMap { _.getAsOpt[User.Id]("user") }
      .map:
        _.map: user =>
          Success.Cookie:
            Impersonations
              .get(user.into(User.ModId))
              .getOrElse:
                seenAt.set(user)
                user

  private val sessionAuthDbProj = Some(BSONDocument("_id" -> false, "user" -> true))
  private val tokenAuthDbProj = Some(BSONDocument("_id" -> false, "userId" -> true))

  private val cookieName = config.getString("cookie.name")

  private val bearerSigners = config.getStringList("oauth.secrets").asScala.toList.map(Algo.hmac)

  private def bearerFromHeader(req: RequestHeader): Option[Auth.Bearer] =
    req.header("Authorization").flatMap { authorization =>
      val prefix = "Bearer "
      if authorization.startsWith(prefix) then
        authorization.stripPrefix(prefix).split(':') match
          case Array(bearer, signed) if bearerSigners.exists(_.sha1(bearer).hash_=(signed)) =>
            Some(Bearer(bearer))
          case _ => None
      else None
    }

  private def bearerAuth(bearer: Bearer): Future[Option[Success]] =
    mongo.oauthColl
      .flatMap:
        _.find(
          BSONDocument("_id" -> AccessTokenId.from(bearer), "scopes" -> "web:mobile"),
          tokenAuthDbProj
        ).one[BSONDocument]
      .map: res =>
        for
          doc <- res
          id <- doc.getAsOpt[User.Id]("userId")
        yield
          seenAt.set(id)
          Success.OAuth(id)

  private def sessionIdFromReq(req: RequestHeader): Option[String] =
    req
      .cookie(cookieName)
      .flatMap:
        case sessionIdRegex(id) => Some(id)
        case _ => None
      .orElse(req.queryParameter(sessionIdKey))

object Auth:
  private val sessionIdKey = "sessionId"
  private val sessionIdRegex = s"""$sessionIdKey=(\\w+)""".r.unanchored
  private val sidKey = "sid"
  private val sidRegex = s"""$sidKey=(\\w+)""".r.unanchored
  private val appealPrefix = "appeal:"

  enum Success(val user: User.Id):
    case Cookie(u: User.Id) extends Success(u)
    case OAuth(u: User.Id) extends Success(u)

  opaque type Bearer = String
  object Bearer extends OpaqueString[Bearer]

  opaque type AccessTokenId = String
  object AccessTokenId extends OpaqueString[AccessTokenId]:
    def from(bearer: Bearer) = AccessTokenId(Algo.sha256(bearer.value).hex)
