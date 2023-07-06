package lila.ws

import com.typesafe.scalalogging.Logger

final class RateLimit(
    maxCredits: Int,
    intervalMillis: Int,
    name: String
):
  import RateLimit.*

  private inline def makeClearAt: Long = nowMillis + intervalMillis

  private var credits: Int    = maxCredits
  private var clearAt: Long   = makeClearAt
  private var logged: Boolean = false

  def apply(msg: => String = ""): Boolean =
    if credits > 0 then
      credits -= 1
      true
    else if clearAt < nowMillis then
      credits = maxCredits
      clearAt = makeClearAt
      true
    else
      if !logged then
        logged = true
        logger.info(s"$name MSG: $msg")
      Monitor rateLimit name
      false

object RateLimit:

  type Cost = Int

  inline def nowMillis = System.currentTimeMillis()

  val logger = Logger(getClass)
