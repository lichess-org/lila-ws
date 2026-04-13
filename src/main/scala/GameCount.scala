package lila.ws

import lila.ws.util.CacheApi
import lila.ws.util.RequestHeader.AuthName

final class GameCount(cacheApi: CacheApi):

  private val seen = cacheApi.notLoadingSync[String, Boolean](5_000, "gameCount"):
    _.expireAfterWrite(11.minutes).build()

  def hit(id: Game.Id, authName: AuthName): Unit =
    if authName.startsWith("takex3-") then
      if seen.underlying.getIfPresent(id.value) != true
      then Monitor.takex3GameCount(authName).increment()
      seen.put(id.value, true)
