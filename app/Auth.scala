package lila.ws

import javax.inject._
import org.joda.time.DateTime
import play.api.mvc.RequestHeader
import reactivemongo.bson._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
final class Auth @Inject() (mongo: Mongo)(implicit executionContext: ExecutionContext) {

  import Mongo._

  private val sidRegex = """.*sessionId=(\w+).*""".r

  def apply(req: RequestHeader): Future[Option[User]] =
    req.cookies get "lila2" match {
      case Some(cookie) =>
        val sid = sidRegex.replaceAllIn(cookie.value, "$1")
        mongo.security {
          _.find(
            BSONDocument("_id" -> sid, "up" -> true),
            Some(BSONDocument("_id" -> false, "user" -> true))
          ).one[BSONDocument]
        } map {
          _.flatMap {
            _.getAs[String]("user") map User.apply
          }
        } map { user =>
          user foreach updateSeenAt
          user
        }
      case None => Future successful None
    }

  private def updateSeenAt(user: User) = mongo.user {
    _.update(ordered = false).one(
      BSONDocument("_id" -> user.id, "enabled" -> true, "seenAt" -> BSONDocument("$lt" -> DateTime.now.minusMinutes(2))),
      BSONDocument("$set" -> BSONDocument("seenAt" -> DateTime.now))
    )
  }
}
