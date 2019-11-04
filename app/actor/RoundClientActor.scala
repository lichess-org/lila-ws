package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, PostStop }
import play.api.libs.json.JsValue

import ipc._

object RoundClientActor {

  import ClientActor._

  case class Player(
      id: Game.PlayerId,
      color: chess.Color
  )

  case class State(
      room: RoomActor.State,
      player: Option[Player],
      userTv: Option[UserTv],
      site: ClientActor.State = ClientActor.State()
  )

  def start(
    roomState: RoomActor.State,
    player: Option[Player],
    userTv: Option[UserTv],
    fromVersion: Option[SocketVersion]
  )(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    import deps._
    val state = State(roomState, player, userTv)
    ClientActor.onStart(deps, ctx)
    req.user foreach { u =>
      queue(_.user, sm.UserSM.Connect(u, ctx.self))
    }
    bus.subscribe(ctx.self, _ room roomState.id)
    queue(_.roundCrowd, RoundCrowd.Connect(roomState.id, req.user, player.map(_.color)))
    RoundEvents.getFrom(Game.Id(roomState.id.value), fromVersion) match {
      case None => clientIn(ClientIn.Resync)
      case Some(events) => events map { versionFor(state, _) } foreach clientIn
    }
    apply(state, deps)
  }

  def versionFor(state: State, msg: ClientIn.RoundVersioned): ClientIn.Payload =
    if (msg.flags.troll && !state.room.isTroll.value) msg.skip
    else if (msg.flags.owner && state.player.isEmpty) msg.skip
    else if (msg.flags.watcher && state.player.isDefined) msg.skip
    else if (msg.flags.player.exists(c => state.player.fold(true)(_.color != c))) msg.skip
    else if (msg.flags.moveBy.exists(c => state.player.fold(true)(_.color == c))) msg.noDests
    else msg.full

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    def gameId = Game.Id(state.room.id.value)
    def fullId = state.player map { p => gameId full p.id }

    msg match {

      case ClientCtrl.Broom(oldSeconds) =>
        if (state.site.lastPing < oldSeconds) Behaviors.stopped
        else {
          // players already send pings to the server, keeping the room alive
          // if (state.player.isEmpty) queue(_.round, LilaIn.KeepAlive(state.room.id))
          Behaviors.same
        }

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.req.flag, ctrl)

      case versioned: ClientIn.RoundVersioned =>
        clientIn(versionFor(state, versioned))
        Behaviors.same

      case ClientIn.OnlyFor(endpoint, payload) =>
        if (endpoint == ClientIn.OnlyFor.Room(state.room.id)) clientIn(payload)
        Behaviors.same

      case crowd: ClientIn.Crowd =>
        if (crowd != state.room.lastCrowd) Behaviors.same
        else {
          deps.clientIn(crowd)
          apply(state.copy(room = state.room.copy(lastCrowd = crowd)), deps)
        }

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
          queue(_.round, LilaIn.RoundMove(fid, uci, blur, lag))
        }
        Behaviors.same

      case ClientOut.RoundPlayerForward(payload) =>
        fullId foreach { fid =>
          queue(_.round, LilaIn.RoundPlayerDo(fid, payload))
        }
        Behaviors.same

      case ClientOut.RoundFlag(color) =>
        queue(_.round, LilaIn.RoundFlag(gameId, color, state.player.map(_.id)))
        Behaviors.same

      case ClientOut.RoundBye =>
        fullId foreach { fid =>
          queue(_.round, LilaIn.RoundBye(fid))
        }
        Behaviors.same

      case ClientOut.ChatSay(msg) =>
        state.player.fold[Option[LilaIn.Round]](
          req.user map { u => LilaIn.WatcherChatSay(state.room.id, u.id, msg) }
        ) { p =>
            Some(LilaIn.PlayerChatSay(state.room.id, req.user.map(_.id).toLeft(p.color), msg))
          } foreach { queue(_.round, _) }
        Behaviors.same

      case ClientOut.ChatTimeout(suspect, reason) =>
        deps.req.user foreach { u =>
          queue(_.round, LilaIn.ChatTimeout(state.room.id, u.id, suspect, reason))
        }
        Behaviors.same

      case ClientOut.RoundBerserk(ackId) =>
        if (state.player.isDefined) req.user foreach { u =>
          clientIn(ClientIn.Ack(ackId))
          queue(_.round, LilaIn.RoundBerserk(gameId, u.id))
        }
        Behaviors.same

      case ClientOut.RoundHold(mean, sd) =>
        fullId foreach { fid =>
          queue(_.round, LilaIn.RoundHold(fid, req.ip, mean, sd))
        }
        Behaviors.same

      case ClientOut.RoundSelfReport(name) =>
        fullId foreach { fid =>
          queue(_.round, LilaIn.RoundSelfReport(fid, req.ip, req.user.map(_.id), name))
        }
        Behaviors.same

      case UserTvNewGame(userId) =>
        if (state.userTv.exists(_.value == userId)) clientIn(ClientIn.Resync)
        Behaviors.same

      // default receive (site)
      case msg: ClientOutSite =>
        val siteState = globalReceive(state.site, deps, ctx, msg)
        if (siteState == state.site) Behaviors.same
        else apply(state.copy(site = siteState), deps)
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state.site, deps, ctx)
      deps.queue(_.roundCrowd, RoundCrowd.Disconnect(state.room.id, deps.req.user, state.player.map(_.color)))
      Behaviors.same
  }
}
