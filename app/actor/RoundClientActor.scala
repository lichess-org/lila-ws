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
      site: ClientActor.State = ClientActor.State()
  )

  def start(
    roomState: RoomActor.State,
    player: Option[Player],
    fromVersion: Option[SocketVersion]
  )(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    RoomActor.onStart(roomState, fromVersion, deps, ctx)
    apply(State(roomState, player), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    def gameId = Game.Id(state.room.id.value)
    def fullId = state.player map { p => gameId full p.id }

    def receive: PartialFunction[ClientMsg, Behavior[ClientMsg]] = {

      case msg: ClientOut.Ping =>
        clientIn(ClientIn.Pong)
        state.player foreach { p =>
          queue(_.round, LilaIn.RoundPlayerPing(gameId, p.color))
        }
        apply(state.copy(site = sitePing(state.site, deps, msg)), deps)

      case ClientCtrl.Broom(oldSeconds) =>
        if (state.site.lastPing < oldSeconds) Behaviors.stopped
        else {
          // players already send pings to the server, keeping the room alive
          if (state.player.isEmpty) queue(_.round, LilaIn.KeepAlive(state.room.id))
          Behaviors.same
        }

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.req.flag, ctrl)

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

      case resync: ClientIn.ResyncPlayer =>
        if (state.player.exists(_.id == resync.playerId)) clientIn(resync)
        Behaviors.same

      // default receive (site)
      case msg: ClientOutSite =>
        val siteState = globalReceive(state.site, deps, ctx, msg)
        if (siteState == state.site) Behaviors.same
        else apply(state.copy(site = siteState), deps)
    }

    RoomActor.receive(state.room, deps).lift(msg).fold(receive(msg)) {
      case (newState, emit) =>
        emit foreach queue.round.offer
        newState.fold(Behaviors.same[ClientMsg]) { roomState =>
          apply(state.copy(room = roomState), deps)
        }
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state.site, deps, ctx)
      RoomActor.onStop(state.room, deps)
      Behaviors.same
  }
}
