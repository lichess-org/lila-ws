package lila.ws.util

import java.util.concurrent.ConcurrentHashMap

final class EventBus[Event, Channel, Subscriber](
    initialCapacity: Int,
    publish: (Subscriber, Event) => Unit
):

  private val entries = ConcurrentHashMap[Channel, Set[Subscriber]](initialCapacity)

  def subscribe(channel: Channel, subscriber: Subscriber): Unit =
    entries.compute(
      channel,
      (_, subs) => Option(subs).fold(Set(subscriber))(_ + subscriber)
    )

  def unsubscribe(channel: Channel, subscriber: Subscriber): Unit =
    entries.computeIfPresent(
      channel,
      (_, subs) =>
        val newSubs = subs - subscriber
        if newSubs.isEmpty then null
        else newSubs
    )

  def publish(channel: Channel, event: Event): Unit =
    Option(entries get channel) foreach:
      _ foreach:
        publish(_, event)

  def size                     = entries.size
  def sizeOf(channel: Channel) = Option(entries get channel).fold(0)(_.size)
