package lila.ws

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
    }
    else if (clearAt < nowMillis) {
      credits = maxCredits
      clearAt = makeClearAt
      true
    }
    else {
      if (!logged) {
        logged = true
        logger.info(s"$name MSG: $msg")
      }
      false
    }

  private val logger = new Logger("RateLimit")
}

object RateLimit {

  type Charge = Cost => Unit
  type Cost = Int

  private type ClearAt = Long

  private def nowMillis = System.currentTimeMillis()
}
