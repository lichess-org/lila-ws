package lila.ws.util

import cats.syntax.option.*

final class EventBus[Event, Channel, Subscriber](
    initialCapacity: Int,
    publish: (Subscriber, Event) => Unit
):

  private val entries = scalalib.ConcurrentMap[Channel, Set[Subscriber]](initialCapacity)
  export entries.size

  def subscribe(channel: Channel, subscriber: Subscriber): Unit =
    entries.compute(channel)(_.fold(Set(subscriber))(_ + subscriber).some)

  def unsubscribe(channel: Channel, subscriber: Subscriber): Unit =
    entries.computeIfPresent(channel): subs =>
      val newSubs = subs - subscriber
      Option.unless(newSubs.isEmpty)(newSubs)

  def publish(channel: Channel, event: Event): Unit =
    entries.get(channel).foreach(_.foreach(publish(_, event)))

  def sizeOf(channel: Channel) = entries.get(channel).fold(0)(_.size)
