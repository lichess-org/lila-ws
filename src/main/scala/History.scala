package lila.ws

import java.util.concurrent.ConcurrentHashMap

final class History[K, V <: ipc.ClientIn.HasVersion](
    historySize: Int,
    initialCapacity: Int
):

  private val histories = ConcurrentHashMap[String, List[V]](initialCapacity)

  def add(key: K, event: V): Unit =
    histories.compute(
      key.toString,
      (_, cur) => event :: Option(cur).getOrElse(Nil).take(historySize)
    )

  def getFrom(key: K, versionOpt: Option[SocketVersion]): Option[List[V]] =
    val allEvents = histories.getOrDefault(key.toString, Nil)
    versionOpt
      .fold(Option(allEvents.take(5))): since =>
        if allEvents.headOption.fold(true)(_.version.value <= since.value)
        then Some(Nil)
        else
          val events = allEvents.takeWhile(_.version.value > since.value)
          if events.sizeIs == events.headOption.fold(0)(_.version.value) - since.value
          then Some(events)
          else None
      .map(_.reverse)

  def stop(key: K) = histories.remove(key.toString)

  def hasEvents(key: K) = Option(histories get key.toString).exists(_.nonEmpty)

  export histories.size

  def allVersions: Array[(String, SocketVersion)] =
    val res = scala.collection.mutable.ArrayBuffer.empty[(String, SocketVersion)]
    histories.forEach: (key, events) =>
      events.headOption foreach { event => res += (key -> event.version) }

    res.toArray

object History:

  val room  = History[RoomId, ipc.ClientIn.Versioned](20, 8192)
  val round = History[Game.Id, ipc.ClientIn.RoundVersioned](20, 65536)
