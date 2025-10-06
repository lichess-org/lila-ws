package lila.ws
package util

import cats.syntax.option.*

/* Batches elements, sends the batch when `timeout` has elapsed since the first element was added. */
final class Batcher[Key, Elem, Batch](
    maxBatchSize: Int, // approx. max number of elements in a batch
    initialCapacity: Int, // number of keys to expect
    timeout: FiniteDuration, // how long to wait for more elements before emitting
    append: (Option[Batch], Elem) => Batch, // how batches are built
    emit: (Key, Batch) => Unit // callback to emit a batch on timeout or maxBatchSize reached
)(using scheduler: Scheduler, ec: Executor):

  final private class Buffer(val batch: Batch, val counter: Int)

  private val buffers = scalalib.ConcurrentMap[Key, Buffer](initialCapacity)

  def add(key: Key, elem: Elem): Unit =
    buffers.compute(key): prev =>
      if prev.isEmpty then scheduler.scheduleOnce(timeout, () => emitAndRemove(key))
      val newBuffer = Buffer(
        append(prev.map(_.batch), elem),
        prev.fold(1)(_.counter + 1)
      )
      if newBuffer.counter >= maxBatchSize then
        emit(key, newBuffer.batch)
        none
      else newBuffer.some

  private def emitAndRemove(key: Key): Unit =
    buffers.computeIfPresent(key): buffer =>
      emit(key, buffer.batch)
      none
