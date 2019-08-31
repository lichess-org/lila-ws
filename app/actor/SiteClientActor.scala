package lila.ws

import akka.actor._
import play.api.libs.json._

import ipc._

final class SiteClientActor(
    clientIn: ActorRef,
    sri: Sri,
    flag: Option[Flag],
    user: Option[User],
    actors: Actors
) extends Actor {

  import SiteClientActor._

  var watchedGames = Set.empty[Game.ID]

  val bus = Bus(context.system)

  override def preStart() = {
    actors.count ! CountActor.Connect
    bus.subscribe(self, _ sri sri)
    bus.subscribe(self, _.all)
    user foreach { u =>
      bus.subscribe(self, _ user u.id)
    }
    flag foreach { f =>
      bus.subscribe(self, _ flag f.value)
    }
  }

  override def postStop() = {
    actors.count ! CountActor.Disconnect
    if (watchedGames.nonEmpty) actors.fen ! FenActor.Unwatch(watchedGames)
    bus unsubscribe self
  }

  val clientInReceive: Receive = {
    case msg: ClientIn => clientIn ! msg
  }

  val clientOutReceive: Receive = {

    case ClientOut.Ping(lag) =>
      clientIn ! ClientIn.Pong
      for { l <- lag; u <- user } actors.lag ! LagActor.Set(u, l)

    case watch: ClientOut.Watch =>
      watchedGames = watchedGames ++ watch.ids
      actors.fen ! watch

    case ClientOut.MoveLat =>
      bus.subscribe(self, _.mlat)
  }

  val receive = clientOutReceive orElse clientInReceive
}

object SiteClientActor {
}
