package lila.ws

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import com.typesafe.scalalogging.Logger

import ipc._
import util.Util.nowSeconds

object ClientActor {

  case class State(
      watchedGames: Set[Game.Id] = Set.empty,
      lastPing: Int = nowSeconds,
      tourReminded: Boolean = false
  )

  def onStart(deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    LilaWsServer.connections.incrementAndGet
    busChansOf(deps.req) foreach { Bus.subscribe(_, ctx.self) }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    LilaWsServer.connections.decrementAndGet
    if (state.watchedGames.nonEmpty) Fens.unwatch(state.watchedGames, ctx.self)
    (Bus.channel.mlat :: busChansOf(req)) foreach { Bus.unsubscribe(_, ctx.self) }
    req.user foreach { user =>
      users.disconnect(user, ctx.self)
      deps.services.friends.onClientStop(user.id)
    }
  }

  def socketControl(state: State, deps: Deps, msg: ClientCtrl): Behavior[ClientMsg] =
    msg match {

      case ClientCtrl.Broom(oldSeconds) =>
        if (state.lastPing < oldSeconds && !deps.req.flag.contains(Flag.api)) Behaviors.stopped
        else Behaviors.same

      case ClientCtrl.Disconnect =>
        deps.clientIn(ClientIn.Disconnect)
        Behaviors.stopped

      case ClientCtrl.ApiDisconnect =>
        // handled by ApiActor only
        Behaviors.same
    }

  def sitePing(state: State, deps: Deps, msg: ClientOut.Ping): State = {
    for { l <- msg.lag; u <- deps.req.user } deps.services.lag(u.id -> l)
    state.copy(lastPing = nowSeconds)
  }

  def globalReceive(
      state: State,
      deps: Deps,
      ctx: ActorContext[ClientMsg],
      msg: ClientOutSite
  ): State = {

    import deps._

    msg match {

      case msg: ClientOut.Ping =>
        clientIn(ClientIn.Pong)
        sitePing(state, deps, msg)

      case ClientOut.Watch(gameIds) =>
        Fens.watch(gameIds, ctx.self)
        state.copy(watchedGames = state.watchedGames ++ gameIds)

      case msg: ClientOut if deps.req.flag.contains(Flag.api) =>
        logger.info(s"API socket doesn't support $msg $req")
        state

      case ClientOut.MoveLat =>
        Bus.subscribe(Bus.channel.mlat, ctx.self)
        state

      case ClientOut.Notified =>
        req.userId foreach services.notified.apply
        state

      case ClientOut.FollowingOnline =>
        req.userId foreach { services.friends.start(_, clientIn) }
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

      case ClientOut.MsgType(dest) =>
        req.user foreach { orig =>
          deps.users.tellOne(dest, ClientIn.MsgType(orig.id))
        }
        state

      case ClientOut.SiteForward(payload) =>
        lilaIn.site(LilaIn.TellSri(req.sri, req.user.map(_.id), payload))
        state

      case ClientOut.UserForward(payload) =>
        req.user foreach { user =>
          lilaIn.site(LilaIn.TellUser(user.id, payload))
        }
        state

      case ClientOut.Ignore =>
        state
    }
  }

  def clientInReceive(state: State, deps: Deps, msg: ClientIn): Option[State] =
    msg match {

      case msg: ClientIn.TourReminder =>
        if (state.tourReminded) None
        else {
          deps clientIn msg
          Some(state.copy(tourReminded = true))
        }

      case in: ClientIn =>
        deps clientIn in
        None
    }

  private val logger = Logger("ClientActor")

  private def busChansOf(req: Req) =
    Bus.channel.all :: Bus.channel.sri(req.sri) :: req.flag.map(Bus.channel.flag).toList

  def Req(req: util.RequestHeader, sri: Sri, user: Option[User]): Req =
    Req(
      name = req.name,
      ip = req.ip,
      sri = sri,
      user = user,
      flag = req.flag
    )

  case class Req(
      name: String,
      ip: Option[IpAddress],
      sri: Sri,
      flag: Option[Flag],
      user: Option[User]
  ) {
    def userId            = user.map(_.id)
    override def toString = s"${user getOrElse "Anon"} $name"
  }

  case class Deps(
      clientIn: ClientEmit,
      req: Req,
      services: Services
  ) {
    def lilaIn     = services.lila
    def users      = services.users
    def roomCrowd  = services.roomCrowd
    def roundCrowd = services.roundCrowd
    def keepAlive  = services.keepAlive
  }
}
