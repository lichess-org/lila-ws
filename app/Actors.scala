package lichess.ws

import akka.actor._

final class Actors(system: ActorSystem) {

  val count = system.actorOf(CountActor.props, "count")
  val lag = system.actorOf(LagActor.props, "lag")
}
