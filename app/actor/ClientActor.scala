package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.stream.scaladsl._
import play.api.Logger

import ipc._
import sm._

object ClientActor {

  def onStart(deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    queue(_.count, CountSM.Connect)
    bus.subscribe(ctx.self, _ sri sri)
    bus.subscribe(ctx.self, _.all)
    flag foreach { f =>
      bus.subscribe(ctx.self, _ flag f.value)
    }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    queue(_.count, CountSM.Disconnect)
    user foreach { u =>
      queue(_.user, UserSM.Disconnect(u, ctx.self))
    }
    if (state.watchedGames.nonEmpty) queue(_.fen, FenSM.Unwatch(state.watchedGames, ctx.self))
    bus unsubscribe ctx.self
  }

  def globalReceive(state: State, deps: Deps, ctx: ActorContext[ClientMsg], msg: ClientOutSite): State = {

    import state._
    import deps._

    msg match {

      case ClientOut.Ping(lag) =>
        clientIn(ClientIn.Pong)
        for { l <- lag; u <- user } queue(_.lag, LagSM.Set(u, l))
        state

      case ClientOut.Watch(gameIds) =>
        queue(_.fen, FenSM.Watch(gameIds, ctx.self))
        state.copy(watchedGames = state.watchedGames ++ gameIds)

      case ClientOut.MoveLat =>
        bus.subscribe(ctx.self, _.mlat)
        state

      case ClientOut.Notified =>
        user foreach { u =>
          queue(_.notified, LilaIn.Notified(u.id))
        }
        state

      case ClientOut.FollowingOnline =>
        user foreach { u =>
          queue(_.friends, LilaIn.Friends(u.id))
        }
        state

      case opening: ClientOut.Opening =>
        Chess(opening) foreach clientIn
        state

      case anaMove: ClientOut.AnaMove =>
        clientIn(Chess(anaMove))
        state

      case anaDrop: ClientOut.AnaDrop =>
        clientIn(Chess(anaDrop))
        state

      case anaDests: ClientOut.AnaDests =>
        clientIn(Chess(anaDests))
        state

      case ClientOut.Forward(payload) =>
        queue(_.site, LilaIn.TellSri(sri, user.map(_.id), payload))
        state

      case ClientOut.Unexpected(msg) =>
        if (state.ignoreLog) state
        else {
          Logger("SiteClient").info(s"Unexpected $msg IP: $ipAddress UA: $userAgent")
          state.copy(ignoreLog = true)
        }

      case ClientOut.Ignore =>
        state
    }
  }

  case class State(
      watchedGames: Set[Game.ID] = Set.empty,
      ignoreLog: Boolean = false
  )

  case class Deps(
      client: SourceQueue[ClientIn],
      queue: Stream.Queues,
      sri: Sri,
      flag: Option[Flag],
      user: Option[User],
      userAgent: String,
      ipAddress: String,
      bus: Bus
  ) {
    def clientIn(msg: ClientIn): Unit = client offer msg
  }
}
