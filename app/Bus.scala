package lila.ws

import akka.actor.typed.ActorRef

import ipc.ClientMsg

object Bus {

  private val impl = new util.EventBus[ClientMsg, Chan, ActorRef[ClientMsg]](
    initialCapacity = 65535,
    publish = (actor, event) => actor ! event
  )

  def subscribe = impl.subscribe _
  def unsubscribe = impl.unsubscribe _

  def publish(chan: Chan, event: ClientMsg): Unit =
    impl.publish(chan, event)

  def publish(chan: ChanSelect, event: ClientMsg): Unit =
    impl.publish(chan(channel), event)

  def publish(msg: Msg): Unit =
    impl.publish(msg.channel, msg.event)

  case class Chan(value: String) extends AnyVal with StringValue

  case class Msg(event: ClientMsg, channel: Chan)

  type ChanSelect = Bus.channel.type => Chan

  object channel {
    def sri(s: Sri) = Chan(s"sri/${s.value}")
    def flag(f: Flag) = Chan(s"flag/$f")
    val mlat = Chan("mlat")
    val all = Chan("all")
    val lobby = Chan("lobby")
    val tv = Chan("tv")
    def room(id: RoomId) = Chan(s"room/$id")
    def tourStanding(id: Tour.ID) = Chan(s"tour-standing/$id")
  }

  def msg(event: ClientMsg, chan: ChanSelect) =
    Msg(event, chan(channel))

  def size = impl.size
  def sizeOf(chan: ChanSelect) = impl sizeOf chan(channel)
}
