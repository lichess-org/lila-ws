package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import org.joda.time.DateTime
import reactivemongo.api.bson.*
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.{ ReadConcern, WriteConcern }
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import scala.util.Success

final class SeenAtUpdate(mongo: Mongo)(using
    context: ExecutionContext,
    scheduler: akka.actor.typed.Scheduler
) extends MongoHandlers:

  private val done: Cache[UserId, Boolean] = Scaffeine()
    .expireAfterWrite(10.minutes)
    .build[UserId, Boolean]()

  def apply(user: UserId): Future[Unit] =
    if done.getIfPresent(user).isDefined then Future successful {}
    else
      done.put(user, true)
      for
        userColl <- mongo.userColl
        now = DateTime.now
        userDoc <- findAndModify(
          coll = userColl,
          selector = BSONDocument("_id" -> user),
          modifier = BSONDocument("$set" -> BSONDocument("seenAt" -> now)),
          fields = BSONDocument("roles" -> true, "_id" -> false)
        )
        isCoach = userDoc.exists(_.getAsOpt[List[String]]("roles").exists(_ contains "ROLE_COACH"))
        _ <-
          if (isCoach)
            mongo.coach(
              _.update(ordered = false).one(
                BSONDocument("_id"  -> user),
                BSONDocument("$set" -> BSONDocument("user.seenAt" -> now))
              )
            )
          else Future successful {}
        _ <-
          if (userDoc.isDefined && streamers.contains(user))
            mongo.streamer(
              _.update(ordered = false).one(
                BSONDocument("_id"  -> user),
                BSONDocument("$set" -> BSONDocument("seenAt" -> now))
              )
            )
          else Future successful ()
      yield ()

  object streamers:

    def contains(user: UserId) = ids contains user

    private var ids = Set.empty[UserId]

    private def fetch: Future[Set[UserId]] =
      mongo.streamer(
        _.distinct[UserId, Set](
          key = "_id",
          selector = Some(
            BSONDocument(
              "listed"           -> true,
              "approval.granted" -> true
            )
          ),
          readConcern = ReadConcern.Local,
          collation = None
        )
      )

    scheduler.scheduleWithFixedDelay(30.seconds, 60.seconds) { () =>
      fetch foreach { res =>
        ids = res
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
