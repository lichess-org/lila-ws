package lila.ws
package evalCache

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import reactivemongo.api.bson.*
import java.time.LocalDateTime

final private class EvalCacheTruster(mongo: Mongo)(using Executor) extends MongoHandlers:

  export cache.get

  private val cache: AsyncLoadingCache[User.Id, Option[Trust]] = Scaffeine()
    .initialCapacity(256)
    .expireAfterWrite(5.minutes)
    .buildAsyncFuture: userId =>
      mongo.userColl
        .flatMap:
          _.find(
            BSONDocument("_id" -> userId),
            Some(BSONDocument("marks" -> 1, "createdAt" -> 1, "title" -> 1, "count.game" -> 1, "roles" -> 1))
          ).one[BSONDocument]
        .map(_ map computeTrust)

  private def computeTrust(user: BSONDocument): Trust =
    if user.getAsOpt[List[String]]("marks").exists(_.nonEmpty) then Trust(-9999)
    else
      Trust:
        seniorityBonus(user) +
          patronBonus(user) +
          titleBonus(user) +
          nbGamesBonus(user) +
          rolesBonus(user)

  // 0 days = -1
  // 1 month = 0
  // 1 year = 2.46
  // 2 years = 3.89
  private def seniorityBonus(user: BSONDocument): Double =
    user
      .getAsOpt[LocalDateTime]("createdAt")
      .fold(-1d): createdAt =>
        math.sqrt(daysBetween(createdAt, LocalDateTime.now) / 30d) - 1

  private def patronBonus(user: BSONDocument): Int =
    user
      .getAsOpt[BSONDocument]("plan")
      .flatMap(_.getAsOpt[Int]("months"))
      .fold(0): months =>
        math.min(months * 5, 20)

  private def titleBonus(user: BSONDocument): Int = if user.contains("title") then 20 else 0

  // 0 games    = -1
  // 100 games  = 0
  // 200 games  = 0.41
  // 1000 games = 2.16
  private def nbGamesBonus(user: BSONDocument): Double =
    user
      .getAsOpt[BSONDocument]("count")
      .flatMap(_.getAsOpt[Int]("games"))
      .fold(-1d): games =>
        math.sqrt(games / 100) - 1

  private def rolesBonus(user: BSONDocument): Int =
    user
      .getAsOpt[Set[String]]("roles")
      .fold(0): roles =>
        if roles("ROLE_LICHESS_TEAM") then 50
        else if roles("ROLE_RELAY") then 40
        else if roles("ROLE_BROADCAST_TIMEOUT") then 30
        else 0
