package lila.ws
package util

import akka.actor.Cancellable
import akka.actor.typed.Scheduler
import scala.collection.immutable.VectorBuilder

final class GroupedWithin()(using Scheduler, Executor):

  def apply[A](nb: Int, interval: FiniteDuration)(emit: Emit[Vector[A]]) =
    GroupedWithinStage[A](nb, interval, emit)

final class GroupedWithinStage[A](
    nb: Int,
    interval: FiniteDuration,
    emit: Emit[Vector[A]]
)(using scheduler: Scheduler, ec: Executor):

  private val buffer: VectorBuilder[A] = new VectorBuilder

  private var scheduledFlush: Cancellable = scheduler.scheduleOnce(interval, () => flush())

  def apply(elem: A): Unit =
    synchronized {
      buffer += elem
      if (buffer.size >= nb) unsafeFlush()
    }

  private def flush(): Unit = synchronized { unsafeFlush() }

  private def unsafeFlush(): Unit =
    if (buffer.nonEmpty)
      emit(buffer.result())
      buffer.clear()
    scheduledFlush.cancel()
    scheduledFlush = scheduler.scheduleOnce(interval, () => flush())
