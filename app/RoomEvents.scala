package lila.ws

import java.util.concurrent.atomic.AtomicReference

import ipc.ClientIn.Versioned

object RoomEvents {

  type Rooms = Map[RoomId, List[Versioned]]

  private val historySize = 30

  private var state: AtomicReference[Rooms] = new AtomicReference(Map.empty)

  def add(roomId: RoomId, event: Versioned): Unit =
    state.getAndUpdate { rooms =>
      rooms.updated(
        roomId,
        event :: rooms.getOrElse(roomId, Nil).take(historySize)
      )
    }

  def getFrom(roomId: RoomId, versionOpt: Option[SocketVersion]): Option[List[Versioned]] = {
    val roomEvents = state.get().getOrElse(roomId, Nil)
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
}
