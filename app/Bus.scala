package lila.ws

import akka.actor.typed.ActorRef
import akka.actor.{ ActorRef => _, _ }
import akka.event._

import ipc._

class Bus extends Extension with EventBus with LookupClassification {

  type Classifier = String
  type Event = Bus.Msg
  type Subscriber = ActorRef[ClientMsg]

  override protected val mapSize = 65535

  protected def compareSubscribers(a: Subscriber, b: Subscriber) = a compareTo b

  def classify(event: Event): Classifier = event.channel.value

  def publish(event: Event, subscriber: Subscriber): Unit = subscriber ! event.payload

  def on(actor: ActorRef[ClientMsg], chan: Bus.Chan): Unit = subscribe(actor, chan.value)

  def off(actor: ActorRef[ClientMsg], chan: Bus.Chan): Unit = unsubscribe(actor, chan.value)

  def apply(payload: ClientMsg, channel: Bus.channel.type => Bus.Chan) = publish(Bus.msg(payload, channel))
}

object Bus extends akka.actor.ExtensionId[Bus] with akka.actor.ExtensionIdProvider {

  case class Chan(value: String) extends AnyVal

  case class Msg(payload: ClientMsg, channel: Chan)

  object channel {
    def sri(s: Sri) = Chan(s"sri/${s.value}")
    def flag(f: Flag) = Chan(s"flag/$f")
    val mlat = Chan("mlat")
    val all = Chan("all")
    val lobby = Chan("lobby")
    val tv = Chan("tv")
    def room(id: RoomId) = Chan(s"room/$id")
    val roundBot = Chan("round-bot")
    def tourStanding(id: Tour.ID) = Chan(s"tour-standing/$id")
  }

  def msg(payload: ClientMsg, channel: Bus.channel.type => Chan) =
    Msg(payload, channel(Bus.channel))

  override def lookup = Bus

  override def createExtension(system: akka.actor.ExtendedActorSystem) = new Bus
}
