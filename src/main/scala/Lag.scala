package lila.ws

import com.github.blemale.scaffeine.Cache

import lila.ws.ipc.LilaIn
import lila.ws.Auth.ApproxSid

final class Lag(lilaRedis: Lila, groupedWithin: util.GroupedWithin)(using cacheApi: util.CacheApi):
  import Lag.*
  
  private type TrustedMillis = Int
  private val trustedRefreshFactor = 0.1f
  private val maxTrustedLagMs = 5_000

  private val trustedStats: Cache[LagKey, TrustedMillis] =
    cacheApi.notLoadingSync[LagKey, TrustedMillis](65_536, "lag.trustedStats"):
      _.expireAfterWrite(1.hour).build[LagKey, TrustedMillis]()

  export trustedStats.getIfPresent as sessionLag

  private val clientReports = groupedWithin[(User.Id, Int)](256, 947.millis): lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))

  export clientReports.apply as recordClientLag

  def recordTrustedLag(millis: Int, userId: Option[LagKey]) =
    Monitor.lag.roundFrameLag(millis)
    val cappedMillis = millis.atMost(maxTrustedLagMs)
    userId.foreach: lagKey =>
      trustedStats.put(
        lagKey,
        sessionLag(lagKey)
          .fold(cappedMillis): prev =>
            (prev * (1 - trustedRefreshFactor) + cappedMillis * trustedRefreshFactor).toInt
      )

object Lag {
    type LagKey = (User.Id, ApproxSid)
}