package lila.ws

import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.actor.typed.{ Behavior, PostStop }

import ipc.*

/* Not actually WS connections.
 * They're lila http stream connections for bots and boards,
 * which lila-ws needs to know about.
 */
object ApiActor:

  def start(deps: Deps): Behavior[ClientMsg] =
    Behaviors.setup: ctx =>
      deps.services.users.connect(deps.user, ctx.self)
      LilaWsServer.updateConnections(origin = "lila", auth = "http-stream", +1)
      apply(deps)

  def onStop(deps: Deps, ctx: ActorContext[ClientMsg]): Unit =
    import deps.*
    LilaWsServer.updateConnections(origin = "lila", auth = "http-stream", -1)
    services.users.disconnect(user, ctx.self)
    services.friends.onClientStop(user)

  private def apply(deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg]: (_, msg) =>
        msg match
          case ClientCtrl.ApiDisconnect => Behaviors.stopped
          case _ =>
            Monitor.clientOutUnhandled("api").increment()
            Behaviors.same
      .receiveSignal { case (ctx, PostStop) =>
        onStop(deps, ctx)
        Behaviors.same
      }

  case class Deps(user: User.Id, services: Services)
