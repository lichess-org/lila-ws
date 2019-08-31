package lila.ws

import akka.actor.{ ActorRef => _, _ }
import akka.actor.typed.ActorRef
import akka.event._

import ipc._

class Bus extends Extension with EventBus with LookupClassification {

  type Event = Bus.Msg
  type Classifier = String
  type Subscriber = ActorRef[ClientMsg]

  override protected val mapSize = 65535

  protected def compareSubscribers(a: Subscriber, b: Subscriber) = a compareTo b

  def classify(event: Event): String = event.channel

  def publish(event: Event, subscriber: Subscriber): Unit = subscriber ! event.payload

  def publish(payload: ClientMsg, channel: Bus.channel.type => Classifier): Unit = publish(Bus.Msg(payload, channel(Bus.channel)))

  def subscribe(actor: ActorRef[ClientMsg], channel: Bus.channel.type => Classifier): Unit = subscribe(actor, channel(Bus.channel))
}

object Bus extends akka.actor.ExtensionId[Bus] with akka.actor.ExtensionIdProvider {

  case class Msg(payload: ClientMsg, channel: String)

  object channel {
    def sri(sri: Sri) = s"sri/${sri.value}"
    def flag(f: String) = s"flag/$f"
    val mlat = "mlat"
    val all = "all"
  }

  override def lookup = Bus

  override def createExtension(system: akka.actor.ExtendedActorSystem) = new Bus
}
