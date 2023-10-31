package lila.ws

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ Behavior, PostStop }
import play.api.libs.json.JsValue

import ipc.*

object LobbyClientActor:

  import ClientActor.*

  case class State(
      idle: Boolean = false,
      site: ClientActor.State = ClientActor.State()
  )

  def start(deps: Deps): Behavior[ClientMsg] =
    Behaviors.setup: ctx =>
      import deps.*
      onStart(deps, ctx)
      req.user foreach { users.connect(_, ctx.self, silently = true) }
      services.lobby.connect(req.sri -> req.user)
      Bus.subscribe(Bus.channel.lobby, ctx.self)
      apply(State(), deps)

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg] { (ctx, msg) =>
        import deps.*

        def forward(payload: JsValue): Unit =
          lilaIn.lobby(LilaIn.TellSri(req.sri, req.user, payload))

        msg match

          case ctrl: ClientCtrl => socketControl(state.site, deps, ctrl)

          case ClientIn.LobbyNonIdle(payload) =>
            if !state.idle then clientIn(payload)
            Behaviors.same

          case ClientIn.OnlyFor(endpoint, payload) =>
            if endpoint == ClientIn.OnlyFor.Endpoint.Lobby then clientIn(payload)
            Behaviors.same

          case in: ClientIn =>
            clientInReceive(state.site, deps, in) match
              case None    => Behaviors.same
              case Some(s) => apply(state.copy(site = s), deps)

          case msg: ClientOut.Ping =>
            clientIn(services.lobby.pong.get)
            apply(state.copy(site = sitePing(state.site, deps, msg)), deps)

          case ClientOut.LobbyJoin(payload) =>
            if deps.req.user.isDefined ||
              deps.req.ip.exists(ip => deps.services.lobby.anonJoinByIpRateLimit(ip.value))
            then forward(payload)
            Behaviors.same

          case ClientOut.LobbyForward(payload) =>
            forward(payload)
            Behaviors.same

          case ClientOut.Idle(value, payload) =>
            forward(payload)
            apply(state.copy(idle = value), deps)

          // default receive (site)
          case msg: ClientOutSite =>
            val siteState = globalReceive(state.site, deps, ctx, msg)
            if siteState == state.site then Behaviors.same
            else apply(state.copy(site = siteState), deps)

          case _ =>
            Monitor.clientOutUnhandled("lobby").increment()
            Behaviors.same

      }
      .receiveSignal { case (ctx, PostStop) =>
        onStop(state.site, deps, ctx)
        Bus.unsubscribe(Bus.channel.lobby, ctx.self)
        deps.services.lobby.disconnect(deps.req.sri)
        Behaviors.same
      }
