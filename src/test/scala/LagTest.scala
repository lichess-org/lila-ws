package lila.ws

class LagTest extends munit.FunSuite:

  import Lag.{ TrustedMillis, nextTrusted }

  private def repeat(from: TrustedMillis, millis: Int, times: Int): TrustedMillis =
    (1 to times).foldLeft(from): (avg, _) =>
      nextTrusted(Some(avg), millis)

  test("first report seeds the average"):
    assertEquals(nextTrusted(None, 42), 42)

  test("reports are clamped"):
    assertEquals(nextTrusted(None, 1_000_000), 5000)
    assertEquals(nextTrusted(None, -1), 0)

  test("a spike barely moves an established average"):
    val next = nextTrusted(Some(20), 1_000_000)
    assert(next > 20 && next < 300, s"a huge report should stay clamped, got $next")

  test("the average falls faster than it rises"):
    val rise = nextTrusted(Some(100), 200) - 100
    val fall = 100 - nextTrusted(Some(100), 0)
    assert(fall > rise, s"expected a fall of $fall to outpace a rise of $rise")

  test("the average never moves past the report"):
    for
      avg <- 0 to 300 by 7
      millis <- 0 to 300 by 7
    do
      val next = nextTrusted(Some(avg), millis)
      assert(
        if millis > avg then avg <= next && next <= millis else millis <= next && next <= avg,
        s"$avg -> $millis gave $next"
      )

  test("the average converges on a sustained report"):
    assertEquals(repeat(from = 16, millis = 30, times = 500), 30)
    assertEquals(repeat(from = 300, millis = 30, times = 500), 30)
    assertEquals(repeat(from = 0, millis = 1, times = 500), 1)

  test("a single spike decays back to the steady state"):
    val spiked = nextTrusted(Some(30), 30_000)
    assert(spiked < 300, s"a 30s spike should not dominate the average, got $spiked")
    assertEquals(repeat(from = spiked, millis = 30, times = 200), 30)
