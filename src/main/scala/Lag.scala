package lila.ws

import chess.Stats
import com.github.blemale.scaffeine.Cache
import com.github.blemale.scaffeine.Scaffeine
import lila.ws.ipc.LilaIn
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger

final class Lag(
    lilaRedis: Lila,
    groupedWithin: util.GroupedWithin
) {

  private val logger = Logger("Lag")

  private val trustedStats: Cache[User.ID, Stats] = Scaffeine()
    .expireAfterAccess(10.minutes)
    .build[User.ID, Stats]()

  private val clientReports = groupedWithin[(User.ID, Int)](128, 947.millis) { lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))
  }

  def siteClientReport(userId: User.ID, centis: Int) = {
    clientReports(userId -> centis)
    trustedStats.getIfPresent(userId).filter(_.samples >= 10) foreach { trusted =>
      if (centis > trusted.mean / 10 * 2)
        logger.info(
          s"https://lichess.org/$userId reported: $centis, trusted: ${Math
            .round(trusted.mean / 10)} samples: ${trusted.samples}, dev: ${trusted.stdDev getOrElse 0}, var: ${trusted.variance getOrElse 0}"
        )
    }
  }

  def trustedLag(millis: Int, userId: Option[User.ID]) = {
    Monitor.lag.roundFrameLag(millis)
    userId foreach { uid =>
      val prev = trustedStats.getIfPresent(uid) getOrElse Stats.empty
      trustedStats.put(uid, prev.record(millis.toFloat))
    }
  }
}
