package lila.ws

import com.github.blemale.scaffeine.Cache
import reactivemongo.api.bson.*
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.{ ReadConcern, WriteConcern }

import java.time.LocalDateTime

import lila.ws.Auth.AccessTokenId

final class SeenAtUpdate(mongo: Mongo)(using
    context: Executor,
    scheduler: Scheduler,
    cacheApi: util.CacheApi
) extends MongoHandlers:

  private val done: Cache[User.Id, Boolean] =
    cacheApi.notLoadingSync[User.Id, Boolean](65_536, "seenAt.done"):
      _.expireAfterWrite(8.minutes).build()

  def set(user: User.Id, oauthToken: Option[AccessTokenId]): Unit =
    if done.getIfPresent(user).isEmpty then
      done.put(user, true)
      val now = LocalDateTime.now
      for
        userColl <- mongo.userColl
        userDoc <- findAndModify(
          coll = userColl,
          selector = BSONDocument("_id" -> user),
          modifier = BSONDocument("$set" -> BSONDocument("seenAt" -> now)),
          fields = BSONDocument("roles" -> true, "_id" -> false)
        )
      do
        oauthToken.foreach: tokenId =>
          mongo.oauthColl.foreach:
            _.update(ordered = false, writeConcern = WriteConcern.Unacknowledged).one(
              BSONDocument("_id" -> tokenId),
              BSONDocument("$set" -> BSONDocument("used" -> now))
            )
        val isCoach = userDoc.exists(_.getAsOpt[List[String]]("roles").exists(_ contains "ROLE_COACH"))
        if isCoach then
          mongo.coach(
            _.update(ordered = false, writeConcern = WriteConcern.Unacknowledged).one(
              BSONDocument("_id" -> user),
              BSONDocument("$set" -> BSONDocument("user.seenAt" -> now))
            )
          )
        if userDoc.isDefined && streamers.contains(user) then
          mongo.streamer(
            _.update(ordered = false, writeConcern = WriteConcern.Unacknowledged).one(
              BSONDocument("_id" -> user),
              BSONDocument("$set" -> BSONDocument("seenAt" -> now))
            )
          )

  object streamers:

    def contains(user: User.Id) = ids contains user

    private var ids = Set.empty[User.Id]

    private def fetch: Future[Set[User.Id]] =
      mongo.streamer(
        _.distinct[User.Id, Set](
          key = "_id",
          selector = Some(
            BSONDocument(
              "listed" -> true,
              "approval.granted" -> true
            )
          ),
          readConcern = ReadConcern.Local,
          collation = None
        )
      )

    scheduler.scheduleWithFixedDelay(30.seconds, 60.seconds) { () =>
      fetch.foreach { res =>
        ids = res
      }
    }

  private def findAndModify(
      coll: BSONCollection,
      selector: BSONDocument,
      modifier: BSONDocument,
      fields: BSONDocument
  ): Future[Option[BSONDocument]] =
    coll
      .findAndModify(
        selector = selector,
        modifier = coll.updateModifier(modifier),
        sort = None,
        fields = Some(fields),
        bypassDocumentValidation = false,
        writeConcern = WriteConcern.Default,
        maxTime = None,
        collation = None,
        arrayFilters = Seq.empty
      )
      .map(_.result[BSONDocument])
