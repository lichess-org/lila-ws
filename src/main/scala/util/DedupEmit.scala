package lila.ws
package util

import akka.actor.typed.Scheduler

final class DedupEmit[A](interval: FiniteDuration)(emit: Emit[A])(using
    scheduler: Scheduler,
    ec: Executor
):

  // don't care about race conditions,
  // this is just about not sending the same message too many times
  private var seen = Set.empty[A]

  def apply(a: A): Unit =
    if (!seen(a))
      seen = seen + a
      emit(a)

  scheduler.scheduleWithFixedDelay(interval, interval) { () => seen = Set.empty }
