package lila.ws

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ Behavior, PostStop }

import ipc.*

object RacerClientActor:

  import ClientActor.*
  import Racer.*

  case class State(
      playerId: PlayerId,
      room: RoomActor.State,
      site: ClientActor.State = ClientActor.State()
  ):
    def raceId = room.room into Racer.Id

  def start(roomState: RoomActor.State, playerId: PlayerId)(
      deps: Deps
  ): Behavior[ClientMsg] =
    Behaviors.setup: ctx =>
      RoomActor.onStart(roomState, None, deps, ctx)
      apply(State(playerId, roomState), deps)

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg] { (ctx, msg) =>
        import deps.*

        def receive: PartialFunction[ClientMsg, Behavior[ClientMsg]] =

          case in: ClientIn =>
            clientInReceive(state.site, deps, in) match
              case None    => Behaviors.same
              case Some(s) => apply(state.copy(site = s), deps)

          case ClientCtrl.Broom(oldSeconds) =>
            if state.site.lastPing < oldSeconds then Behaviors.stopped
            else
              keepAlive.racer(state.room.room)
              Behaviors.same

          case ctrl: ClientCtrl => socketControl(state.site, deps, ctrl)

          case ClientOut.RacerScore(score) =>
            services.lila.racer(LilaIn.RacerScore(state.raceId, state.playerId, score))
            Behaviors.same

          case ClientOut.RacerJoin =>
            services.lila.racer(LilaIn.RacerJoin(state.raceId, state.playerId))
            Behaviors.same

          case ClientOut.RacerStart =>
            services.lila.racer(LilaIn.RacerStart(state.raceId, state.playerId))
            Behaviors.same

          // default receive (site)
          case msg: ClientOutSite =>
            val siteState = globalReceive(state.site, deps, ctx, msg)
            if siteState == state.site then Behaviors.same
            else apply(state.copy(site = siteState), deps)

          case _ =>
            Monitor.clientOutUnhandled("racer").increment()
            Behaviors.same

        RoomActor
          .receive(state.room, deps)
          .lift(msg)
          .fold(receive(msg)): (newState, emit) =>
            emit foreach lilaIn.racer
            newState.fold(Behaviors.same[ClientMsg]): roomState =>
              apply(state.copy(room = roomState), deps)
      }
      .receiveSignal { case (ctx, PostStop) =>
        onStop(state.site, deps, ctx)
        RoomActor.onStop(state.room, deps, ctx)
        Behaviors.same
      }
