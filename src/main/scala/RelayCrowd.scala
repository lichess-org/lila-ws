package lila.ws

import cats.syntax.all.*

/* For ongoing broadcasts, store in the round mongodb document the current number of viewers.
 * Lila will display it and store time stats. */
final private class RelayCrowd(roomCrowd: RoomCrowd, mongo: Mongo)(using ex: Executor, scheduler: Scheduler):

  scheduler.scheduleWithFixedDelay(20.seconds, 14.seconds): () =>
    updateRelays()

  private def updateRelays() = for
    ids <- storage.ongoingIds
    members = roomCrowd.getNbMembers(ids)
    _ <- storage.setMembers(members)
  yield ()

  private object storage extends MongoHandlers:

    import reactivemongo.api.bson.*

    /* selects the last non-finished round of each active broadcast.
     * or the last round that was finished less than 2 hours ago. */
    def ongoingIds: Future[Set[RoomId]] = for
      tourColl <- mongo.relayTourColl
      roundColl <- mongo.relayRoundColl
      now = nowInstant
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
                  "as" -> "round",
                  "let" -> BSONDocument("tourId" -> "$_id"),
                  "pipeline" -> List(
                    BSONDocument(
                      "$match" -> BSONDocument(
                        "$expr" -> BSONDocument("$eq" -> BSONArray("$tourId", "$$tourId"))
                      )
                    ),
                    // the following matcher finds the round to monitor
                    BSONDocument(
                      "$match" -> BSONDocument(
                        "$or" -> BSONArray(
                          // either finished less than 2 hours ago
                          BSONDocument("finishedAt" -> BSONDocument("$gt" -> now.minusHours(2))),
                          // or unfinished, and
                          BSONDocument(
                            "finishedAt" -> BSONDocument("$exists" -> false),
                            BSONDocument(
                              "$or" -> BSONArray(
                                // either started less than 8 hours ago
                                BSONDocument("startedAt" -> BSONDocument("$gt" -> now.minusHours(8))),
                                // or will start in the next 1 hour
                                BSONDocument("startsAt" -> BSONDocument("$lt" -> now.plusHours(1)))
                              )
                            )
                          )
                        )
                      )
                    ),
                    BSONDocument("$sort" -> BSONDocument("order" -> 1)),
                    BSONDocument("$limit" -> 5),
                    BSONDocument("$project" -> BSONDocument("_id" -> true))
                  )
                )
              )
            ,
            UnwindField("round"),
            Limit(150),
            Group(BSONNull)("ids" -> PushField("round._id"))
          )
        .collect[List](maxDocs = 1)
    yield result.headOption.flatMap(_.getAsOpt[Set[RoomId]]("ids")).getOrElse(Set.empty)

    // couldn't make update.many work
    def setMembers(all: Map[RoomId, Int]): Future[Unit] = mongo.relayRoundColl.flatMap: coll =>
      val crowdAt = BSONDocument("crowdAt" -> nowInstant)
      all.toSeq.traverse_ { (id, crowd) =>
        val set = BSONDocument("crowd" -> crowd) ++ crowdAt
        coll.update.one(
          q = BSONDocument("_id" -> id),
          u = BSONDocument("$set" -> set)
        )
      }
