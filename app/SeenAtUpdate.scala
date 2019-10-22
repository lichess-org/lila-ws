package lila.ws

import javax.inject._
import org.joda.time.DateTime
import play.api.mvc.RequestHeader
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{ ReadConcern, WriteConcern }
import reactivemongo.bson._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
final class SeenAtUpdate @Inject() (mongo: Mongo)( implicit
  context: ExecutionContext,
  system: akka.actor.ActorSystem
) {

  import Mongo._

  def apply(user: User): Future[Unit] = for {
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
    isStreamer = userDoc.isDefined && streamers.contains(user)
    _ <- if (isStreamer) mongo.streamer(_.update(ordered = false).one(
      BSONDocument("_id" -> user.id),
      BSONDocument("$set" -> BSONDocument("seenAt" -> now))
    )) else Future successful (())
  } yield ()

  object streamers {

    def contains(user: User) = ids contains user.id

    private var ids = Set.empty[User.ID]

    private def fetch: Future[Set[User.ID]] = mongo.streamer(
      _.distinct[User.ID, Set](
        key = "_id",
        selector = Some(BSONDocument(
          "listed" -> true,
          "approval.granted" -> true
        )),
        readConcern = ReadConcern.Local,
        collation = None
      )
    )

    system.scheduler.scheduleWithFixedDelay(10.seconds, 100.seconds) { () =>
      fetch foreach { res =>
        ids = res
      }
    }
  }

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
