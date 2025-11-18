package lila.ws
package util

import scalalib.future.TimeoutException

import scala.util.control.NonFatal

object LilaFuture:

  // implementation borrowed from pekko,
  // but using the typed scheduler instead of the classic one
  def after[T](duration: FiniteDuration)(
      value: => Future[T]
  )(using ec: Executor, scheduler: Scheduler): Future[T] =
    if duration.isFinite && duration.length < 1 then
      try value
      catch case NonFatal(t) => Future.failed(t)
    else
      val p = Promise[T]()
      scheduler.scheduleOnce(
        duration,
        () =>
          p.completeWith {
            try value
            catch case NonFatal(t) => Future.failed(t)
          }
      )
      p.future

extension [A](fua: Future[A])

  // like scalalib.future.withTimeout, but using pekko's typed scheduler
  def withTimeout(duration: FiniteDuration, error: => String)(using Executor, Scheduler): Future[A] =
    Future.firstCompletedOf(
      Seq(
        fua,
        LilaFuture.after(duration)(Future.failed(TimeoutException(s"$error timeout after $duration")))
      )
    )
