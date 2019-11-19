package lila.ws

import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import akka.actor.typed.{ ActorRef, Behavior, PostStop }
import akka.stream.scaladsl._
import play.api.Logger

import ipc._
import sm._
import util.Util.nowSeconds

object ClientActor {

  def busChansOf(req: Req) =
    Bus.channel.all :: Bus.channel.sri(req.sri) :: req.flag.map(Bus.channel.flag).toList

  def onStart(deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    Connections.connect
    busChansOf(deps.req) foreach { Bus.subscribe(_, ctx.self) }
  }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit = {
    import deps._
    Connections.disconnect
    req.user foreach { users.disconnect(_, ctx.self) }
    if (state.watchedGames.nonEmpty) fens.unwatch(state.watchedGames, ctx.self)
    (Bus.channel.mlat :: busChansOf(req)) foreach { Bus.unsubscribe(_, ctx.self) }
  }

  def socketControl(state: State, flag: Option[Flag], msg: ClientCtrl): Behavior[ClientMsg] = msg match {

    case ClientCtrl.Broom(oldSeconds) =>
      if (state.lastPing < oldSeconds && !flag.contains(Flag.api)) Behaviors.stopped
      else Behaviors.same

    case ClientCtrl.Disconnect =>
      Behaviors.stopped
  }

  def sitePing(state: State, deps: Deps, msg: ClientOut.Ping): State = {
    for { l <- msg.lag; u <- deps.req.user } deps.services.lag(u.id, l)
    state.copy(lastPing = nowSeconds)
  }

  def wrong(loggerName: String, state: State, deps: Deps, msg: ClientMsg)(update: State => Behavior[ClientMsg]): Behavior[ClientMsg] = {
    Monitor.clientOutUnexpected.increment()
    if (state.ignoreLog) Behaviors.same
    else {
      Logger(s"${loggerName}ClientActor").info(s"Wrong $msg ${deps.req}")
      update(state.copy(ignoreLog = true))
    }
  }

  def globalReceive(state: State, deps: Deps, ctx: ActorContext[ClientMsg], msg: ClientOutSite): State = {

    import state._
    import deps._

    msg match {

      case msg: ClientOut.Ping =>
        clientIn(ClientIn.Pong)
        sitePing(state, deps, msg)

      case ClientOut.Watch(gameIds) =>
        fens.watch(gameIds, ctx.self)
        state.copy(watchedGames = state.watchedGames ++ gameIds)

      case msg: ClientOut if deps.req.flag.contains(Flag.api) =>
        if (state.ignoreLog) state
        else {
          Logger("SiteClient").info(s"API socket doesn't support $msg $req")
          state.copy(ignoreLog = true)
        }

      case ClientOut.MoveLat =>
        Bus.subscribe(Bus.channel.mlat, ctx.self)
        state

      case ClientOut.Notified =>
        req.userId foreach services.notified.apply
        state

      case ClientOut.FollowingOnline =>
        req.userId foreach services.friends.apply
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

      case ClientOut.SiteForward(payload) =>
        lilaIn.site(LilaIn.TellSri(req.sri, req.user.map(_.id), payload))
        state

      case ClientOut.Unexpected(msg) =>
        Monitor.clientOutUnexpected.increment()
        if (state.ignoreLog) state
        else {
          Logger("ClientActor").info(s"Unexpected $msg $req")
          state.copy(ignoreLog = true)
        }

      case ClientOut.Ignore =>
        state
    }
  }

  def clientInReceive(state: State, deps: Deps, msg: ClientIn): Option[State] = msg match {

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

  case class State(
      watchedGames: Set[Game.Id] = Set.empty,
      lastPing: Int = nowSeconds,
      ignoreLog: Boolean = false,
      tourReminded: Boolean = false
  )

  def Req(req: play.api.mvc.RequestHeader, sri: Sri, user: Option[User], flag: Option[Flag] = None): Req = Req(
    name = lila.ws.util.Util.reqName(req),
    ip = IpAddress(req.remoteAddress),
    sri = sri,
    user = user,
    flag = flag
  )

  case class Req(
      name: String,
      ip: IpAddress,
      sri: Sri,
      flag: Option[Flag],
      user: Option[User]
  ) {
    def userId = user.map(_.id)
    override def toString = s"${user getOrElse "Anon"} $name"
  }

  case class Deps(
      clientIn: Emit[ClientIn],
      req: Req,
      services: Services
  ) {
    def lilaIn = services.lila
    def users = services.users
    def fens = services.fens
    def roomCrowd = services.roomCrowd
    def roundCrowd = services.roundCrowd
    def keepAlive = services.keepAlive
  }
}
