package lila.ws

import java.util.concurrent.ConcurrentHashMap

import ipc.ClientIn.RoundVersioned

object RoundEvents {

  private val historySize = 30

  private val stacks = new ConcurrentHashMap[String, List[RoundVersioned]](32768)

  def add(gameId: Game.Id, event: RoundVersioned): Unit =
    stacks.compute(gameId.value, (_, cur) =>
      event :: Option(cur).getOrElse(Nil).take(historySize))

  def getFrom(gameId: Game.Id, versionOpt: Option[SocketVersion]): Option[List[RoundVersioned]] = {
    val roomEvents = stacks.getOrDefault(gameId.value,  Nil)
    versionOpt
      .fold(Option(roomEvents.take(5))) { since =>
        if (roomEvents.headOption.fold(true)(_.version <= since)) Some(Nil)
        else {
          val events = roomEvents.takeWhile(_.version > since)
          if (events.size == events.headOption.fold(0)(_.version.value) - since.value) Some(events)
          else None
        }
      }
      .map(_.reverse)
  }

  def reset(gameId: Game.Id) = stacks.put(gameId.value, Nil)
  def stop(gameId: Game.Id) = stacks.remove(gameId.value)

  def hasEvents(gameId: Game.Id) = Option(stacks.get(gameId.value)).exists(_.nonEmpty)

  def size = stacks.size
}
