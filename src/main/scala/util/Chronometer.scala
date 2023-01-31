package lila.ws
package util

object Chronometer:

  case class Lap[A](result: A, nanos: Long):

    def millis = (nanos / 1000000).toInt
    def micros = (nanos / 1000).toInt

    def logIfSlow(threshold: Int)(msg: A => String) =
      if (millis >= threshold) println(s"<${millis}ms> ${msg(result)}")
      this

    def pp: A =
      println(s"chrono $showDuration")
      result

    def pp(msg: String): A =
      println(s"chrono $msg - $showDuration")
      result
    def ppIfGt(msg: String, duration: FiniteDuration): A =
      if (nanos > duration.toNanos) pp(msg)
      else result

    def showDuration: String = if (millis >= 1) f"$millis ms" else s"$micros micros"

  case class FuLap[A](lap: Future[Lap[A]]) extends AnyVal:

    def logIfSlow(threshold: Int)(msg: A => String)(using Executor) =
      lap.foreach(_.logIfSlow(threshold)(msg))
      this

    def pp(using Executor): Future[A]              = lap map (_.pp)
    def pp(msg: String)(using Executor): Future[A] = lap map (_ pp msg)
    def ppIfGt(msg: String, duration: FiniteDuration)(using Executor): Future[A] =
      lap map (_.ppIfGt(msg, duration))

    def result(using Executor) = lap.map(_.result)

  def apply[A](f: => Future[A])(using Executor): FuLap[A] =
    val start = nowNanos
    FuLap(f map { Lap(_, nowNanos - start) })

  def sync[A](f: => A): Lap[A] =
    val start = nowNanos
    val res   = f
    Lap(res, nowNanos - start)

  def syncEffect[A](f: => A)(effect: Lap[A] => Unit): A =
    val lap = sync(f)
    effect(lap)
    lap.result

  def nowNanos: Long = System.nanoTime()
