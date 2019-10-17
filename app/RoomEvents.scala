package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import scala.concurrent.duration._

import ipc.ClientIn.Versioned

object RoomEvents {

  private val historySize = 30

  private val cache: Cache[String, List[Versioned]] =
    Scaffeine()
      .expireAfterWrite(10.minutes)
      .build[String, List[Versioned]]()

  def add(roomId: RoomId, event: Versioned): Unit = synchronized {
    cache.put(
      roomId.value,
      event :: cache.getIfPresent(roomId.value).fold(List.empty[Versioned])(_ take historySize)
    )
  }

  def getFrom(roomId: RoomId, versionOpt: Option[SocketVersion]): Option[List[Versioned]] = {
    val roomEvents = cache.getIfPresent(roomId.value).getOrElse(Nil)
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

  def reset(roomId: RoomId) = cache.invalidate(roomId.value)
}
