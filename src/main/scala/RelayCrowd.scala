package lila.ws

import org.apache.pekko.actor.typed.Scheduler
import cats.syntax.all.*

final private class RelayCrowd(roomCrowd: RoomCrowd, mongo: Mongo)(using ex: Executor, scheduler: Scheduler):

  scheduler.scheduleWithFixedDelay(20.seconds, 11.seconds): () =>
    updateRelays()

  private def updateRelays() = for
    ids <- storage.ongoingIds
    members = roomCrowd.getNbMembers(ids)
    _ <- storage.setMembers(members)
  yield ()

  private object storage extends MongoHandlers:

    import reactivemongo.api.bson.*

    def ongoingIds: Future[Set[RoomId]] = for
      tourColl  <- mongo.relayTourColl
      roundColl <- mongo.relayRoundColl
      result <- tourColl
        .aggregateWith[BSONDocument](): framework =>
          import framework.*
          List(
            Match(BSONDocument("active" -> true, "tier" -> BSONDocument("$exists" -> true))),
            Sort(Descending("tier")),
            PipelineOperator:
              BSONDocument(
                "$lookup" -> BSONDocument(
                  "from" -> roundColl.name,
                  "as"   -> "round",
                  "let"  -> BSONDocument("tourId" -> "$_id"),
                  "pipeline" -> List(
                    BSONDocument(
                      "$match" -> BSONDocument(
                        "finished" -> false,
                        "$expr"    -> BSONDocument("$eq" -> BSONArray("$tourId", "$$tourId"))
                      )
                    ),
                    BSONDocument("$sort"    -> BSONDocument("createdAt" -> 1)),
                    BSONDocument("$limit"   -> 1),
                    BSONDocument("$project" -> BSONDocument("_id" -> true))
                  )
                )
              )
            ,
            UnwindField("round"),
            Limit(100),
            Group(BSONNull)("ids" -> PushField("round._id"))
          )
        .collect[List](maxDocs = 1)
    yield result.headOption.flatMap(_.getAsOpt[Set[RoomId]]("ids")).getOrElse(Set.empty)

    // couldn't make update.many work
    def setMembers(all: Map[RoomId, Int]): Future[Unit] = mongo.relayRoundColl.flatMap: coll =>
      all.toSeq.traverse_ { (id, crowd) =>
        coll.update.one(
          q = BSONDocument("_id" -> id),
          u = BSONDocument("$set" -> BSONDocument("crowd" -> crowd))
        )
      }
