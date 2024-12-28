package lila.ws
package util

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.Scheduler

import java.util.concurrent.ConcurrentHashMap

/* Batches elements, sends the batch when `timeout` has elapsed since the last element was added. */
final class Batcher[Key, Elem, Batch](
    maxBatchSize: Int,                      // max number of elements in a batch
    initialCapacity: Int,                   // number of keys to expect
    timeout: FiniteDuration,                // how long to wait for more elements before emitting
    append: (Option[Batch], Elem) => Batch, // how batches are built
    emit: (Key, Batch) => Unit              // callback to emit a batch on timeout or maxBatchSize reached
)(using scheduler: Scheduler, ec: Executor):

  final private class Buffer(val batch: Batch, val counter: Int, cancelable: Cancellable):
    export cancelable.cancel

  private val buffers = ConcurrentHashMap[Key, Buffer](initialCapacity)

  def add(key: Key, elem: Elem): Unit =
    val newBuffer = buffers.compute(
      key,
      (_, buffer) =>
        val prev = Option(buffer)
        prev.foreach(_.cancel())
        Buffer(
          append(prev.map(_.batch), elem),
          prev.fold(1)(_.counter + 1),
          scheduler.scheduleOnce(timeout, () => emitAndRemove(key))
        )
    )
    if newBuffer.counter >= maxBatchSize then emitAndRemove(key)

  private def emitAndRemove(key: Key): Unit =
    buffers.computeIfPresent(
      key,
      (_, buffer) =>
        buffer.cancel()
        emit(key, buffer.batch)
        null
    )
