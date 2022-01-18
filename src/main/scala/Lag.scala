package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import lila.ws.ipc.LilaIn
import scala.concurrent.duration._

final class Lag(
    lilaRedis: Lila,
    groupedWithin: util.GroupedWithin
) {

  private type TrustedMillis = Int
  private val trustedRefreshFactor = 0.1f

  private val trustedStats: Cache[User.ID, TrustedMillis] = Scaffeine()
    .expireAfterWrite(1 hour)
    .build[User.ID, TrustedMillis]()

  private val clientReports = groupedWithin[(User.ID, Int)](128, 947.millis) { lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))
  }

  def sessionLag(userId: User.ID) = trustedStats getIfPresent userId

  def recordClientLag = clientReports.apply _

  def recordTrustedLag(millis: Int, userId: Option[User.ID]) = {
    Monitor.lag.roundFrameLag(millis)
    userId foreach { uid =>
      trustedStats.put(
        uid,
        sessionLag(uid)
          .fold(millis) { prev =>
            (prev * (1 - trustedRefreshFactor) + millis * trustedRefreshFactor).toInt
          }
      )
    }
  }
}
