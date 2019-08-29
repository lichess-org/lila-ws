package lichess.ws

import javax.inject._
import akka.actor._

@Singleton
final class Actors @Inject() (system: ActorSystem) {

  val count = system.actorOf(CountActor.props, "count")
  val lag = system.actorOf(LagActor.props, "lag")
}
