package lila.ws
package evalCache

import org.joda.time.{ DateTime, Days }
import scala.concurrent.duration.*
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import reactivemongo.api.bson.*

final private class EvalCacheTruster(
    mongo: Mongo
)(using scala.concurrent.ExecutionContext)
    extends MongoHandlers:

  export cache.get

  private val cache: AsyncLoadingCache[User.Id, Option[Trust]] = Scaffeine()
    .initialCapacity(256)
    .expireAfterWrite(10.minutes)
    .buildAsyncFuture { userId =>
      mongo.userColl
        .flatMap {
          _.find(
            BSONDocument("_id" -> userId),
            Some(BSONDocument("marks" -> 1, "createdAt" -> 1, "title" -> 1, "count.game" -> 1))
          ).one[BSONDocument]
        }
        .map(_ map computeTrust)
    }

  private def computeTrust(user: BSONDocument): Trust =
    if (user.contains("marks")) Trust(-9999)
    else
      Trust {
        seniorityBonus(user) +
          patronBonus(user) +
          titleBonus(user) +
          nbGamesBonus(user)
      }

  // 0 days = -1
  // 1 month = 0
  // 1 year = 2.46
  // 2 years = 3.89
  private def seniorityBonus(user: BSONDocument): Double =
    user.getAsOpt[DateTime]("createdAt").fold(-1d) { createdAt =>
      math.sqrt(Days.daysBetween(createdAt, DateTime.now).getDays.toDouble / 30) - 1
    }

  private def patronBonus(user: BSONDocument): Int =
    user.getAsOpt[BSONDocument]("plan").flatMap(_.getAsOpt[Int]("months")).fold(0) { months =>
      math.min(months * 5, 20)
    }

  private def titleBonus(user: BSONDocument): Int = if user.contains("title") then 20 else 0

  // 0 games    = -1
  // 100 games  = 0
  // 200 games  = 0.41
  // 1000 games = 2.16
  private def nbGamesBonus(user: BSONDocument): Double =
    user.getAsOpt[BSONDocument]("count").flatMap(_.getAsOpt[Int]("games")).fold(-1d) { games =>
      math.sqrt(games / 100) - 1
    }
