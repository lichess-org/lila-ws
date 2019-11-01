package lila.ws
package sm

import java.util.concurrent.atomic.AtomicInteger

object CountSM {

  private val count = new AtomicInteger(0)

  def connect: Unit = {
    count.incrementAndGet
  }
  def disconnect: Unit = {
    count.decrementAndGet
  }

  def get: Int = count.get
}
