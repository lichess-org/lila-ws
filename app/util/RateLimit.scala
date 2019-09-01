package lila.ws

import play.api.Logger
import scala.concurrent.duration.Duration

final class RateLimit(
    maxCredits: Int,
    duration: Duration,
    name: String
) {
  import RateLimit._

  private def makeClearAt = nowMillis + duration.toMillis

  private var credits = maxCredits
  private var clearAt = makeClearAt
  private var logged = false

  def apply(msg: => String = ""): Boolean =
    if (credits > 0) {
      credits -= 1
      true
    } else if (clearAt < nowMillis) {
      credits = maxCredits
      clearAt = makeClearAt
      true
    } else {
      if (!logged) {
        logged = true
        Logger("RateLimit").info(s"$name MSG: $msg")
      }
      false
    }
}

object RateLimit {

  type Charge = Cost => Unit
  type Cost = Int

  private type ClearAt = Long

  private def nowMillis = System.currentTimeMillis()

  import akka.stream._
  import akka.stream.scaladsl._

  def flow[A](limiter: RateLimit): Flow[A, A, _] =
    Flow[A].collect {
      case a if limiter(a.toString) => a
    }
}
