package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, PostStop }

import ipc.*

object ChallengeClientActor:

  import ClientActor.*

  case class State(
      owner: Boolean,
      room: RoomActor.State,
      site: ClientActor.State = ClientActor.State()
  )

  def start(roomState: RoomActor.State, owner: Boolean, fromVersion: Option[SocketVersion])(
      deps: Deps
  ): Behavior[ClientMsg] =
    Behaviors.setup { ctx =>
      RoomActor.onStart(roomState, fromVersion, deps, ctx)
      apply(State(owner, roomState), deps)
    }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg] { (ctx, msg) =>
        import deps.*

        def receive: PartialFunction[ClientMsg, Behavior[ClientMsg]] =

          case in: ClientIn =>
            clientInReceive(state.site, deps, in) match
              case None    => Behaviors.same
              case Some(s) => apply(state.copy(site = s), deps)

          case ClientCtrl.Disconnect =>
            // lila tries to close the round room, because there's no game with that ID yet
            // ignore it so we stay connected to the challenge
            Behaviors.same

          case ClientCtrl.Broom(oldSeconds) =>
            if (state.site.lastPing < oldSeconds) Behaviors.stopped
            else
              keepAlive challenge state.room.room
              Behaviors.same

          case ctrl: ClientCtrl => socketControl(state.site, deps, ctrl)

          case ClientOut.ChallengePing =>
            if (state.owner) services.challengePing(state.room.room)
            Behaviors.same

          // default receive (site)
          case msg: ClientOutSite =>
            val siteState = globalReceive(state.site, deps, ctx, msg)
            if (siteState == state.site) Behaviors.same
            else apply(state.copy(site = siteState), deps)

          case _ =>
            Monitor.clientOutUnhandled("challenge").increment()
            Behaviors.same

        RoomActor.receive(state.room, deps).lift(msg).fold(receive(msg)) { case (newState, emit) =>
          emit foreach lilaIn.challenge.apply
          newState.fold(Behaviors.same[ClientMsg]) { roomState =>
            apply(state.copy(room = roomState), deps)
          }
        }

      }
      .receiveSignal { case (ctx, PostStop) =>
        onStop(state.site, deps, ctx)
        RoomActor.onStop(state.room, deps, ctx)
        Behaviors.same
      }
