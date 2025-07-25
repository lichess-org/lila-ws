package lila.ws

import org.apache.pekko.actor.typed.ActorRef

import ipc.ClientMsg

object Bus:

  type Chan = String

  private val impl = util.EventBus[ClientMsg, Chan, ActorRef[ClientMsg]](
    initialCapacity = 65535,
    publish = (actor, event) => actor ! event
  )
  export impl.{ size, subscribe, unsubscribe }

  def publish(chan: Chan, event: ClientMsg): Unit =
    impl.publish(chan, event)

  def publish(chan: ChanSelect, event: ClientMsg): Unit =
    impl.publish(chan(channel), event)

  def publish(msg: Msg): Unit =
    impl.publish(msg.channel, msg.event)

  case class Msg(event: ClientMsg, channel: Chan)

  type ChanSelect = Bus.channel.type => Chan

  object channel:
    inline def sri(inline s: Sri) = s"sri/$s"
    inline def flag(inline f: Flag) = s"flag/$f"
    val mlat = "mlat"
    val all = "all"
    val roundPlayer = "roundPlayer"
    val lobby = "lobby"
    val tv = "tv"
    val tvChannels = "tv-channels"
    inline def userTv(inline u: UserTv) = s"userTv/$u"
    inline def room(inline id: RoomId) = s"room/$id"
    inline def tourStanding(inline id: Tour.Id) = s"tour-standing/$id"
    inline def externalChat(inline id: RoomId) = s"external-chat/$id"

  def msg(event: ClientMsg, chan: ChanSelect) =
    Msg(event, chan(channel))

  def sizeOf(chan: ChanSelect) = impl.sizeOf(chan(channel))

  // distinct bus for internal events
  val internal = util.EventBus[Matchable, Chan, PartialFunction[Matchable, Unit]](
    initialCapacity = 16,
    publish = (listener, event) => listener.lift(event)
  )
