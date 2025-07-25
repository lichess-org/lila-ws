package lila.ws

import cats.data.NonEmptyList
import cats.syntax.option.*

final class History[K, V <: ipc.ClientIn.HasVersion](
    historySize: Int,
    initialCapacity: Int
):

  private val histories = scalalib.ConcurrentMap[String, List[V]](initialCapacity)

  export histories.size

  def add(key: K, event: V): Unit =
    histories.compute(key.toString): cur =>
      (event :: cur.getOrElse(Nil)).take(historySize).some

  def add(key: K, events: NonEmptyList[V]): Unit =
    histories.compute(key.toString): cur =>
      (events.toList ::: cur.getOrElse(Nil)).take(historySize).some

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

  def hasEvents(key: K) = histories.get(key.toString).exists(_.nonEmpty)

  def allVersions: Array[(String, SocketVersion)] =
    val res = scala.collection.mutable.ArrayBuffer.empty[(String, SocketVersion)]
    histories.foreach: (key, events) =>
      events.headOption.foreach { event => res += (key -> event.version) }

    res.toArray

object History:

  val room = History[RoomId, ipc.ClientIn.Versioned](20, 8192)
  val round = History[Game.Id, ipc.ClientIn.RoundVersioned](20, 65536)
