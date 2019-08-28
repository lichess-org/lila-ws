package lichess.ws

import akka.actor._
import akka.event._

class Bus extends Extension with EventBus with LookupClassification {

  type Event = Bus.Msg
  type Classifier = String
  type Subscriber = ActorRef

  override protected val mapSize = 65535

  protected def compareSubscribers(a: Subscriber, b: Subscriber) = a compareTo b

  def classify(event: Event): String = event.channel

  def publish(event: Event, subscriber: Subscriber) = subscriber ! event.payload
}

object Bus extends ExtensionId[Bus] with ExtensionIdProvider {

  case class Msg(payload: Any, channel: String)

  object channel {
    def sri(sri: Sri) = s"sri/${sri.value}"
    def user(id: String) = s"user/$id"
  }

  override def lookup = Bus

  override def createExtension(system: ExtendedActorSystem) = new Bus
}
