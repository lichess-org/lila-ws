package lila.ws

import akka.actor.typed.ActorRef

import ipc.LilaOut

object LilaBus {

  import Lila.Chan

  private val impl = new util.EventBus[LilaOut, Chan, Emit[LilaOut]](
    initialCapacity = 8,
    publish = (emit, event) => emit(event)
  )

  def subscribe = impl.subscribe _
  def unsubscribe = impl.unsubscribe _

  def publish(chan: Chan, event: LilaOut): Unit =
    impl.publish(chan, event)

  def size = impl.size
  def sizeOf(chan: Chan) = impl sizeOf chan
}
