package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }

import lila.ws.ipc.LilaIn
import lila.ws.util.Domain

final class Lag(lilaRedis: Lila, groupedWithin: util.GroupedWithin):
  private type TrustedMillis = Int
  private val trustedSpikeRefreshFactor  = 0.05f
  private val trustedNormalRefreshFactor = 0.1f
  private val maxMillis                  = 5000

  private val trustedStats: Cache[User.Id, TrustedMillis] =
    Scaffeine().expireAfterWrite(1.hour).build[User.Id, TrustedMillis]()

  export trustedStats.getIfPresent as sessionLag

  private val clientReports = groupedWithin[(User.Id, Int)](256, 947.millis) { lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))
  }

  export clientReports.apply as recordClientLag

  def recordTrustedLag(millis: Int, userId: Option[User.Id], domain: Domain) =
    Monitor.lag.roundFrameLag(millis, domain)
    userId.foreach { uid =>
      trustedStats.put(
        uid,
        sessionLag(uid).fold(millis) { prev =>
          val weight =
            if millis < prev then trustedNormalRefreshFactor else trustedSpikeRefreshFactor
          val cappedMillis = millis.min(maxMillis)
          (prev * (1 - weight) + cappedMillis * weight).toInt
        }
      )
    }
