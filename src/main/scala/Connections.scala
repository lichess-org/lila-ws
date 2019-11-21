package lila.ws

import java.util.concurrent.atomic.AtomicInteger

object Connections {

  private val count = new AtomicInteger(0)

  def connect: Unit = count.incrementAndGet

  def disconnect: Unit = count.decrementAndGet

  def get: Int = count.get
}
