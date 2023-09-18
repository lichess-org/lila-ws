package lila.ws.util

class SmallBoundedQueueSetTest extends munit.FunSuite:

  private given munit.Compare[SmallBoundedQueueSet[Int], List[Int]] with
    def isEqual(obtained: SmallBoundedQueueSet[Int], expected: List[Int]): Boolean =
      obtained.value == expected

  test("empty"):
    assertEquals(SmallBoundedQueueSet.empty[Int], List[Int]())

  test("add"):
    val (q, evicted) = SmallBoundedQueueSet.empty[Int].addAndReturnEvicted(List(1), maxSize = 10)
    assertEquals(q, List(1))
    assertEquals(evicted, Set.empty)
    val (q2, e2) = q.addAndReturnEvicted(List(2, 3, 4, 5), maxSize = 10)
    assertEquals(q2, List(2, 3, 4, 5, 1))
    assertEquals(e2, Set.empty)
    val (q3, e3) = q2.addAndReturnEvicted(List(6, 7, 8, 9), maxSize = 9)
    assertEquals(q3, List(6, 7, 8, 9, 2, 3, 4, 5, 1))
    assertEquals(e3, Set.empty)
    val (q4, e4) = q3.addAndReturnEvicted(List(10), maxSize = 8)
    assertEquals(q4, List(10, 6, 7, 8, 9, 2, 3, 4))
    assertEquals(e4, Set(5, 1))
    val (q5, e5) = q4.addAndReturnEvicted(List(), maxSize = 3)
    assertEquals(q5, List(10, 6, 7))
    assertEquals(e5, Set(8, 9, 2, 3, 4))
    val (q6, e6) = q5.addAndReturnEvicted(List(99), maxSize = 0)
    assertEquals(q6, List.empty[Int])
    assertEquals(e6, Set(10, 6, 7))
