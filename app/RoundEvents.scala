package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import scala.concurrent.duration._

import ipc.ClientIn.RoundVersioned

object RoundEvents {

  private val historySize = 30

  private val cache: Cache[String, List[RoundVersioned]] =
    Scaffeine()
      .expireAfterWrite(10.minutes)
      .build[String, List[RoundVersioned]]()

  def add(gameId: Game.Id, event: RoundVersioned): Unit = synchronized {
    cache.put(
      gameId.value,
      event :: cache.getIfPresent(gameId.value).fold(List.empty[RoundVersioned])(_ take historySize)
    )
  }

  def getFrom(gameId: Game.Id, versionOpt: Option[SocketVersion]): Option[List[RoundVersioned]] = {
    val roomEvents = cache.getIfPresent(gameId.value).getOrElse(Nil)
    val lastEventVersion = roomEvents.headOption.map(_.version)
    versionOpt
      .fold(Option(roomEvents.take(5))) { since =>
        if (lastEventVersion.fold(true)(_ <= since)) Some(Nil)
        else {
          val events = roomEvents.takeWhile(_.version > since)
          if (events.size == events.headOption.fold(0)(_.version.value) - since.value) Some(events)
          else None
        }
      }
      .map(_.reverse)
  }

  def reset(gameId: Game.Id) = cache.put(gameId.value, Nil)
  def stop(gameId: Game.Id) = cache.invalidate(gameId.value)

  def hasEvents(gameId: Game.Id) = cache.getIfPresent(gameId.value).exists(_.nonEmpty)
}
