package lila.ws

import lila.ws.util.{ CacheApi, RequestHeader }

final class GameCount(cacheApi: CacheApi):

  private val seen = cacheApi.notLoadingSync[String, Boolean](10_000, "gameCount"):
    _.expireAfterWrite(11.minutes).build()

  def hit(id: Game.Id, auth: RequestHeader.AuthName): Unit =
    if auth == Auth.takex3Scope then
      if seen.underlying.getIfPresent(id.value) != true
      then Monitor.takex3GameCount.increment()
      seen.put(id.value, true)
