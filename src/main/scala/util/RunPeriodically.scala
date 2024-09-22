package lila.ws
package util

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.Scheduler

/** Run a function after a counter hits a ceiling, or periodically after a fixed time interval, if counter > 0
  */
final class RunPeriodically()(using Scheduler, Executor):
  def apply(counterMax: Int, interval: FiniteDuration)(flush: () => Unit) =
    RunPeriodicallyStage(counterMax, interval, flush)

final class RunPeriodicallyStage(counterMax: Int, interval: FiniteDuration, run: () => Unit)(using
    scheduler: Scheduler,
    ec: Executor
):

  private var counter: Int = 0

  private var scheduledFlush: Cancellable = scheduler.scheduleOnce(interval, () => run())

  def increment(): Unit =
    synchronized:
      counter += 1
      if counter >= counterMax then unsafeFlush()

  private def flush(): Unit = synchronized { unsafeFlush() }

  private def unsafeFlush(): Unit =
    if counter > 0 then
      run()
      counter = 0
    scheduledFlush.cancel()
    scheduledFlush = scheduler.scheduleOnce(interval, () => flush())
