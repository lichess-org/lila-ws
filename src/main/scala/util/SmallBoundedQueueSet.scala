package lila.ws
package util

/*
 * A FIFO queue with a maximum size, which is also a set.
 * When adding elements, if the queue is full, the oldest elements are evicted and returned.
 * Existing elements are ignored.
 * maxSize should be kept small.
 * For larger queues, implement another one with LinkedHashSet.
 */
opaque type SmallBoundedQueueSet[A] = List[A]

object SmallBoundedQueueSet:

  def empty[A]: SmallBoundedQueueSet[A] = List.empty

  extension [A](q: SmallBoundedQueueSet[A])
    def value: List[A] = q
    def addAndReturnEvicted(newElements: Iterable[A], maxSize: Int): (SmallBoundedQueueSet[A], Set[A]) =
      val curSet    = q.toSet
      val newSet    = (newElements.toSet -- curSet).take(maxSize)
      val nbToEvict = Math.max(0, curSet.size + newSet.size - maxSize)
      if nbToEvict == 0 then (newSet.toList ::: q) -> Set.empty
      else
        q.splitAt(curSet.size - nbToEvict) match
          case (kept, evicted) =>
            (newSet.toList ::: kept) -> evicted.toSet
