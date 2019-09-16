package lila.ws

import javax.inject._
import org.joda.time.DateTime
import play.api.mvc.RequestHeader
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.WriteConcern
import reactivemongo.bson._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
final class Auth @Inject() (mongo: Mongo)(implicit executionContext: ExecutionContext) {

  import Mongo._

  private val sidRegex = """.*sessionId=(\w+).*""".r

  def apply(req: RequestHeader, flag: Option[Flag]): Future[Option[User]] =
    if (flag contains Flag.api) Future successful None
    else req.cookies get "lila2" match {
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

  private def updateSeenAt(user: User): Future[Unit] = for {
    userColl <- mongo.userColl
    now = DateTime.now
    userDoc <- findAndModify(
      coll = userColl,
      selector = BSONDocument(
        "_id" -> user.id,
        "enabled" -> true,
        "seenAt" -> BSONDocument("$lt" -> now.minusMinutes(2))
      ),
      modifier = BSONDocument("$set" -> BSONDocument("seenAt" -> now)),
      fields = BSONDocument("roles" -> true, "_id" -> false),
    )
    isCoach = userDoc.exists(_.getAs[List[String]]("roles").exists(_ contains "ROLE_COACH"))
    _ <- if (isCoach) mongo.coach(_.update(ordered = false).one(
      BSONDocument("_id" -> user.id),
      BSONDocument("$set" -> BSONDocument("user.seenAt" -> now))
    )) else Future successful (())
  } yield ()

  private def findAndModify(
    coll: BSONCollection,
    selector: BSONDocument,
    modifier: BSONDocument,
    fields: BSONDocument
  ): Future[Option[BSONDocument]] =
    coll.findAndModify(
      selector = selector,
      modifier = coll.updateModifier(modifier),
      sort = None,
      fields = Some(fields),
      bypassDocumentValidation = false,
      writeConcern = WriteConcern.Default,
      maxTime = None,
      collation = None,
      arrayFilters = Seq.empty
    ) map (_.result[BSONDocument])
}
