package lila.ws

import akka.actor._
import akka.event._

class Bus extends Extension with EventBus with LookupClassification {

  type Event = Bus.Msg
  type Classifier = String
  type Subscriber = ActorRef

  override protected val mapSize = 65535

  protected def compareSubscribers(a: Subscriber, b: Subscriber) = a compareTo b

  def classify(event: Event): String = event.channel

  def publish(event: Event, subscriber: Subscriber): Unit = subscriber ! event.payload

  def publish(payload: Any, channel: Bus.channel.type => Classifier): Unit = publish(Bus.Msg(payload, channel(Bus.channel)))

  def subscribe(actor: ActorRef, channel: Bus.channel.type => Classifier): Unit = subscribe(actor, channel(Bus.channel))
}

object Bus extends ExtensionId[Bus] with ExtensionIdProvider {

  case class Msg(payload: Any, channel: String)

  object channel {
    def sri(sri: Sri) = s"sri/${sri.value}"
    def user(id: User.ID) = s"user/$id"
    def flag(f: String) = s"flag/$f"
    val mlat = "mlat"
    val all = "all"
  }

  override def lookup = Bus

  override def createExtension(system: ExtendedActorSystem) = new Bus
}
