package lila.ws

import akka.actor._
import akka.actor.typed.scaladsl.adapter._
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
  import actors._

  var watchedGames = Set.empty[Game.ID]

  val bus = Bus(context.system)

  override def preStart() = {
    countActor ! CountActor.Connect
    bus.subscribe(self, _ sri sri)
    bus.subscribe(self, _.all)
    user foreach { u =>
      userActor ! UserActor.Connect(u, self)
    }
    flag foreach { f =>
      bus.subscribe(self, _ flag f.value)
    }
  }

  override def postStop() = {
    countActor ! CountActor.Disconnect
    user foreach { u =>
      userActor ! UserActor.Disconnect(u, self)
    }
    if (watchedGames.nonEmpty) fenActor ! FenActor.Unwatch(watchedGames, self)
    bus unsubscribe self
  }

  val clientInReceive: Receive = {
    case msg: ClientIn => clientIn ! msg
  }

  val clientOutReceive: Receive = {

    case ClientOut.Ping(lag) =>
      clientIn ! ClientIn.Pong
      for { l <- lag; u <- user } lagActor ! LagActor.Set(u, l)

    case ClientOut.Watch(gameIds) =>
      watchedGames = watchedGames ++ gameIds
      fenActor ! FenActor.Watch(gameIds, self)

    case ClientOut.MoveLat =>
      bus.subscribe(self, _.mlat)

    case ClientOut.Notified =>
      user foreach { u =>
        actors.lilaSite ! LilaIn.Notified(u.id)
      }

    case ClientOut.FollowingOnline =>
      user foreach { u =>
        actors.lilaSite ! LilaIn.Friends(u.id)
      }

    case opening: ClientOut.Opening =>
      Chess(opening) foreach clientIn.!

    case anaMove: ClientOut.AnaMove =>
      Chess(anaMove) foreach clientIn.!

    case anaDrop: ClientOut.AnaDrop =>
      Chess(anaDrop) foreach clientIn.!

    case anaDests: ClientOut.AnaDests =>
      clientIn ! Chess(anaDests)

    case ClientOut.Forward(payload) =>
      actors.lilaSite ! LilaIn.TellSri(sri, user.map(_.id), payload)
  }

  val receive = clientOutReceive orElse clientInReceive
}

object SiteClientActor {
}
