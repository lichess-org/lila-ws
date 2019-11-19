package lila.ws

import akka.actor.{ ActorSystem, Cancellable }
import javax.inject._
import scala.collection.immutable.VectorBuilder
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

@Singleton
final class GroupedWithin @Inject() ()(implicit system: ActorSystem, ec: ExecutionContext) {

  def apply[A](nb: Int, interval: FiniteDuration)(emit: Emit[Vector[A]]) =
    new GroupedWithinStage[A](nb, interval, emit)
}

final class GroupedWithinStage[A](
    nb: Int,
    interval: FiniteDuration,
    emit: Emit[Vector[A]]
)(implicit system: ActorSystem, ec: ExecutionContext) {

  private val buffer: VectorBuilder[A] = new VectorBuilder

  private var scheduledFlush: Cancellable = system.scheduler.scheduleOnce(interval)(flush)

  def apply(elem: A): Unit = synchronized {
    buffer += elem
    if (buffer.size >= nb) flush
  }

  private def flush: Unit = synchronized {
    if (buffer.nonEmpty) {
      emit(buffer.result())
      buffer.clear()
    }
    scheduledFlush.cancel
    scheduledFlush = system.scheduler.scheduleOnce(interval)(flush)
  }
}
