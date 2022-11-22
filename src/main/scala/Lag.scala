package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import lila.ws.ipc.LilaIn
import scala.concurrent.duration.*

final class Lag(
    lilaRedis: Lila,
    groupedWithin: util.GroupedWithin
):

  private type TrustedMillis = Int
  private val trustedRefreshFactor = 0.1f

  private val trustedStats: Cache[User.Id, TrustedMillis] = Scaffeine()
    .expireAfterWrite(1 hour)
    .build[User.Id, TrustedMillis]()

  private val clientReports = groupedWithin[(User.Id, Int)](128, 947.millis) { lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))
  }

  def sessionLag(userId: User.Id) = trustedStats getIfPresent userId

  def recordClientLag = clientReports.apply

  def recordTrustedLag(millis: Int, userId: Option[User.Id]) =
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
