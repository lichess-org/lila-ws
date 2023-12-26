package lila.ws

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import com.typesafe.scalalogging.Logger
import ipc.*
import lila.ws.util.SmallBoundedQueueSet

object ClientActor:

  case class State(
      watchedGames: SmallBoundedQueueSet[Game.Id] = SmallBoundedQueueSet.empty,
      lastPing: Int = nowSeconds,
      tourReminded: Boolean = false
  )

  def onStart(deps: Deps, ctx: ActorContext[ClientMsg]): Unit =
    LilaWsServer.connections.incrementAndGet
    busChansOf(deps.req) foreach { Bus.subscribe(_, ctx.self) }

  def onStop(state: State, deps: Deps, ctx: ActorContext[ClientMsg]): Unit =
    import deps.*
    LilaWsServer.connections.decrementAndGet
    Fens.unwatch(state.watchedGames.value, ctx.self)
    (Bus.channel.mlat :: Bus.channel.tvChannels :: busChansOf(req)) foreach { Bus.unsubscribe(_, ctx.self) }
    req.user.foreach: user =>
      users.disconnect(user, ctx.self)
      deps.services.friends.onClientStop(user)

  def socketControl(state: State, deps: Deps, msg: ClientCtrl): Behavior[ClientMsg] = msg match

    case ClientCtrl.Broom(oldSeconds) =>
      if state.lastPing < oldSeconds && !deps.req.flag.contains(Flag.api) then Behaviors.stopped
      else Behaviors.same

    case ClientCtrl.Disconnect =>
      deps.clientIn(ClientIn.Disconnect)
      Behaviors.stopped

    case ClientCtrl.ApiDisconnect =>
      // handled by ApiActor only
      Behaviors.same

  def sitePing(state: State, deps: Deps, msg: ClientOut.Ping): State =
    for
      l <- msg.lag
      u <- deps.req.user
    do deps.services.lag.recordClientLag(u -> l)
    state.copy(lastPing = nowSeconds)

  def globalReceive(
      state: State,
      deps: Deps,
      ctx: ActorContext[ClientMsg],
      msg: ClientOutSite
  ): State =

    import deps.*

    msg match

      case msg: ClientOut.Ping =>
        clientIn(ClientIn.Pong)
        sitePing(state, deps, msg)

      case ClientOut.Watch(gameIds) =>
        val (newWatched, evicted) = state.watchedGames.addAndReturnEvicted(gameIds, 16)
        Fens.watch(gameIds, ctx.self)
        Fens.unwatch(evicted, ctx.self)
        state.copy(watchedGames = newWatched)

      case ClientOut.StartWatchingTvChannels =>
        Bus.subscribe(Bus.channel.tvChannels, ctx.self)
        state

      case msg: ClientOut if deps.req.flag.contains(Flag.api) =>
        logger.info(s"API socket doesn't support $msg $req")
        state

      case ClientOut.MoveLat =>
        Bus.subscribe(Bus.channel.mlat, ctx.self)
        state

      case ClientOut.Notified =>
        req.user foreach services.notified.apply
        state

      case ClientOut.FollowingOnline =>
        req.user foreach { services.friends.start(_, clientIn) }
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

      case evalGet: ClientOut.EvalGet =>
        services.evalCache.get(req.sri, evalGet, clientIn)
        state

      case evalGet: ClientOut.EvalGetMulti =>
        services.evalCache.getMulti(req.sri, evalGet, clientIn)
        state

      case evalPut: ClientOut.EvalPut =>
        req.user.foreach: user =>
          services.evalCache.put(req.sri, user, evalPut)
        state

      case ClientOut.MsgType(dest) =>
        req.user.foreach: orig =>
          deps.users.tellOne(dest, ClientIn.MsgType(orig))
        state

      case ClientOut.SiteForward(payload) =>
        lilaIn.site(LilaIn.TellSri(req.sri, req.user, payload))
        state

      case ClientOut.UserForward(payload) =>
        req.user.foreach: user =>
          lilaIn.site(LilaIn.TellUser(user, payload))
        state

      case ClientOut.StormKey(key, pad) =>
        clientIn(ClientIn.StormKey(deps.services.stormSign(key, pad)))
        state

      case ClientOut.Ignore =>
        state

      case unexpected => sys error s"$this can't handle $unexpected"

  def clientInReceive(state: State, deps: Deps, msg: ClientIn): Option[State] = msg match

    case msg: ClientIn.TourReminder =>
      if state.tourReminded then None
      else
        deps clientIn msg
        Some(state.copy(tourReminded = true))

    case in: ClientIn =>
      deps clientIn in
      None

  private val logger = Logger("ClientActor")

  private def busChansOf(req: Req) =
    Bus.channel.all :: Bus.channel.sri(req.sri) :: req.flag.map(Bus.channel.flag).toList

  def Req(header: util.RequestHeader, sri: Sri, auth: Option[Auth.Success]): Req =
    Req(header, sri, auth, header.flag)

  case class Req(
      header: util.RequestHeader,
      sri: Sri,
      auth: Option[Auth.Success],
      flag: Option[Flag]
  ):
    export header.{ ip, isLichessMobile, name }
    def user: Option[User.Id] = auth.map(_.user)
    def isOauth = auth.exists:
      case Auth.Success.OAuth(_) => true
      case _                     => false
    override def toString = s"${user.fold("Anon")(_.value)} $name"

  case class Deps(
      clientIn: ClientEmit,
      req: Req,
      services: Services
  ):
    def lilaIn     = services.lila
    def users      = services.users
    def roomCrowd  = services.roomCrowd
    def roundCrowd = services.roundCrowd
    def keepAlive  = services.keepAlive
