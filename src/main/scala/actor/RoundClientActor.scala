package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, PostStop }
import play.api.libs.json.JsValue

import ipc._

object RoundClientActor {

  import ClientActor._

  case class State(
      room: RoomActor.State,
      player: Option[Game.RoundPlayer],
      userTv: Option[UserTv],
      site: ClientActor.State = ClientActor.State()
  ) {
    def busChans: List[Bus.Chan] =
      Bus.channel.room(room.id) :: player.flatMap(_.tourId).map(Bus.channel.tourStanding).toList
  }

  def start(
    roomState: RoomActor.State,
    player: Option[Game.RoundPlayer],
    userTv: Option[UserTv],
    fromVersion: Option[SocketVersion]
  )(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    val state = State(roomState, player, userTv)
    ClientActor.onStart(deps, ctx)
    req.user foreach { users.connect(_, ctx.self) }
    state.busChans foreach { Bus.subscribe(_, ctx.self) }
    roundCrowd.connect(roomState.id, req.user, player.map(_.color))
    History.round.getFrom(Game.Id(roomState.id.value), fromVersion) match {
      case None => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(state, _) } foreach clientIn
    }
    apply(state, deps)
  }

  def versionFor(state: State, msg: ClientIn.RoundVersioned): ClientIn.Payload =
    if ((msg.flags.troll && !state.room.isTroll.value) ||
      (msg.flags.owner && state.player.isEmpty) ||
      (msg.flags.watcher && state.player.isDefined) ||
      msg.flags.player.exists(c => state.player.fold(true)(_.color != c))) msg.skip
    else if (msg.flags.moveBy.exists(c => state.player.fold(true)(_.color == c))) msg.noDests
    else msg.full

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    def gameId = Game.Id(state.room.id.value)
    def fullId = state.player map { p => gameId full p.id }

    msg match {

      case ClientCtrl.Broom(oldSeconds) =>
        if (state.site.lastPing < oldSeconds) Behaviors.stopped
        else Behaviors.same

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.req.flag, ctrl)

      case versioned: ClientIn.RoundVersioned =>
        clientIn(versionFor(state, versioned))
        Behaviors.same

      case ClientIn.OnlyFor(endpoint, payload) =>
        if (endpoint == ClientIn.OnlyFor.Room(state.room.id)) clientIn(payload)
        Behaviors.same

      case crowd: ClientIn.Crowd =>
        if (crowd == state.room.lastCrowd) Behaviors.same
        else {
          deps.clientIn(crowd)
          apply(state.copy(room = state.room.copy(lastCrowd = crowd)), deps)
        }

      case SetTroll(v) =>
        apply(state.copy(room = state.room.copy(isTroll = v)), deps)

      case resync: ClientIn.RoundResyncPlayer =>
        if (state.player.exists(_.id == resync.playerId)) clientIn(resync)
        Behaviors.same

      case gone: ClientIn.RoundGone =>
        if (state.player.exists(_.id != gone.playerId)) clientIn(gone)
        Behaviors.same

      case in: ClientIn =>
        clientIn(in)
        Behaviors.same

      case ClientOut.RoundMove(uci, blur, lag, ackId) =>
        fullId foreach { fid =>
          clientIn(ClientIn.Ack(ackId))
          lilaIn.round(LilaIn.RoundMove(fid, uci, blur, lag))
        }
        Behaviors.same

      case ClientOut.RoundPlayerForward(payload) =>
        fullId foreach { fid =>
          lilaIn.round(LilaIn.RoundPlayerDo(fid, payload))
        }
        Behaviors.same

      case ClientOut.RoundFlag(color) =>
        lilaIn.round(LilaIn.RoundFlag(gameId, color, state.player.map(_.id)))
        Behaviors.same

      case ClientOut.RoundBye =>
        fullId foreach { fid =>
          lilaIn.round(LilaIn.RoundBye(fid))
        }
        Behaviors.same

      case ClientOut.ChatSay(msg) =>
        state.player.fold[Option[LilaIn.Round]](
          req.user map { u => LilaIn.WatcherChatSay(state.room.id, u.id, msg) }
        ) { p =>
            Some(LilaIn.PlayerChatSay(state.room.id, req.user.map(_.id).toLeft(p.color), msg))
          } foreach lilaIn.round
        Behaviors.same

      case ClientOut.ChatTimeout(suspect, reason) =>
        deps.req.user foreach { u =>
          lilaIn.round(LilaIn.ChatTimeout(state.room.id, u.id, suspect, reason))
        }
        Behaviors.same

      case ClientOut.RoundBerserk(ackId) =>
        if (state.player.isDefined) req.user foreach { u =>
          clientIn(ClientIn.Ack(ackId))
          lilaIn.round(LilaIn.RoundBerserk(gameId, u.id))
        }
        Behaviors.same

      case ClientOut.RoundHold(mean, sd) =>
        fullId foreach { fid =>
          lilaIn.round(LilaIn.RoundHold(fid, req.ip, mean, sd))
        }
        Behaviors.same

      case ClientOut.RoundSelfReport(name) =>
        fullId foreach { fid =>
          lilaIn.round(LilaIn.RoundSelfReport(fid, req.ip, req.user.map(_.id), name))
        }
        Behaviors.same

      case ClientOut.PalantirPing =>
        deps.req.user map { Palantir.respondToPing(state.room.id, _) } foreach clientIn
        Behaviors.same

      case UserTvNewGame(userId) =>
        if (state.userTv.exists(_.value == userId)) clientIn(ClientIn.Resync)
        Behaviors.same

      // default receive (site)
      case msg: ClientOutSite =>
        val siteState = globalReceive(state.site, deps, ctx, msg)
        if (siteState == state.site) Behaviors.same
        else apply(state.copy(site = siteState), deps)

      case msg => wrong("Round", state.site, deps, msg) { s =>
        apply(state.copy(site = s), deps)
      }
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state.site, deps, ctx)
      state.busChans foreach { Bus.unsubscribe(_, ctx.self) }
      deps.roundCrowd.disconnect(state.room.id, deps.req.user, state.player.map(_.color))
      Behaviors.same
  }

  case class UserTvNewGame(userId: User.ID) extends ClientMsg
}
