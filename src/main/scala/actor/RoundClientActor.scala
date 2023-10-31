package lila.ws

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ Behavior, PostStop }
import ipc.*
import chess.Centis

object RoundClientActor:

  import ClientActor.*

  case class State(
      room: RoomActor.State,
      player: Option[Game.RoundPlayer],
      userTv: Option[UserTv],
      site: ClientActor.State = ClientActor.State()
  ):
    def busChans: List[Bus.Chan] =
      Bus.channel.room(room.room) ::
        player.flatMap(_.tourId).fold(List.empty[Bus.Chan]) { tourId =>
          List(
            Bus.channel.tourStanding(tourId),
            Bus.channel.externalChat(tourId into RoomId)
          )
        } :::
        player
          .flatMap(_.extRoomId)
          .fold(List.empty[Bus.Chan]) { roomId =>
            List(Bus.channel.externalChat(roomId))
          } :::
        userTv.map(Bus.channel.userTv).toList

  def start(
      roomState: RoomActor.State,
      player: Option[Game.RoundPlayer],
      userTv: Option[UserTv],
      from: Either[Option[SocketVersion], JsonString]
  )(deps: Deps): Behavior[ClientMsg] =
    Behaviors.setup: ctx =>
      import deps.*
      val state = State(roomState, player, userTv)
      onStart(deps, ctx)
      req.user foreach { users.connect(_, ctx.self) }
      state.busChans foreach { Bus.subscribe(_, ctx.self) }
      roundCrowd.connect(roomState.room, req.user, player.map(_.color))
      from match
        case Left(version) =>
          History.round.getFrom(roomState.room.into(Game.Id), version) match
            case None         => clientIn(ClientIn.Resync)
            case Some(events) => events map { versionFor(state, _) } foreach clientIn
        case Right(gameState) => clientIn(ClientIn.roundFull(gameState))
      apply(state, deps)

  def versionFor(state: State, msg: ClientIn.RoundVersioned): ClientIn.Payload =
    if (msg.flags.troll && !state.room.isTroll.value) ||
      (msg.flags.owner && state.player.isEmpty) ||
      (msg.flags.watcher && state.player.isDefined) ||
      msg.flags.player.exists(c => state.player.fold(true)(_.color != c))
    then msg.skip
    else if msg.flags.moveBy.exists(c => state.player.fold(true)(_.color == c)) then msg.noDests
    else msg.full

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg] { (ctx, msg) =>
        import deps.*

        def gameId = state.room.room into Game.Id
        def fullId = state.player.map: p =>
          gameId full p.id

        msg match

          case ClientOut.RoundPongFrame(lagMillis) =>
            services.lag.recordTrustedLag(lagMillis, req.user)
            Behaviors.same

          case ClientCtrl.Broom(oldSeconds) =>
            if state.site.lastPing < oldSeconds then Behaviors.stopped
            else Behaviors.same

          case ctrl: ClientCtrl => socketControl(state.site, deps, ctrl)

          case versioned: ClientIn.RoundVersioned =>
            clientIn(versionFor(state, versioned))
            Behaviors.same

          case ClientIn.OnlyFor(endpoint, payload) =>
            if endpoint == ClientIn.OnlyFor.Endpoint.Room(state.room.room) then clientIn(payload)
            Behaviors.same

          case crowd: ClientIn.Crowd =>
            if crowd == state.room.lastCrowd then Behaviors.same
            else
              deps.clientIn(crowd)
              apply(state.copy(room = state.room.copy(lastCrowd = crowd)), deps)

          case SetTroll(v) =>
            apply(state.copy(room = state.room.copy(isTroll = v)), deps)

          case resync: ClientIn.RoundResyncPlayer =>
            if state.player.exists(_.id == resync.playerId) then clientIn(resync)
            Behaviors.same

          case gone: ClientIn.RoundGone =>
            if state.player.exists(_.id != gone.playerId) then clientIn(gone)
            Behaviors.same

          case goneIn: ClientIn.RoundGoneIn =>
            if state.player.exists(_.id != goneIn.playerId) then clientIn(goneIn)
            Behaviors.same

          case in: ClientIn =>
            clientIn(in)
            Behaviors.same

          case ClientOut.RoundMove(uci, blur, clientLag, ackId) =>
            fullId.foreach: fid =>
              clientIn(ClientIn.RoundPingFrameNoFlush)
              clientIn(ClientIn.Ack(ackId))
              val frameLagCentis = req.user.flatMap(deps.services.lag.sessionLag).map(Centis.ofMillis(_))
              val lag            = clientLag withFrameLag frameLagCentis
              lilaIn.round(LilaIn.RoundMove(fid, uci, blur, lag))
            Behaviors.same

          case ClientOut.RoundPlayerForward(payload) =>
            fullId.foreach: fid =>
              lilaIn.round(LilaIn.RoundPlayerDo(fid, payload))
            Behaviors.same

          case ClientOut.RoundFlag(color) =>
            lilaIn.round(LilaIn.RoundFlag(gameId, color, state.player.map(_.id)))
            Behaviors.same

          case ClientOut.RoundBye =>
            fullId.foreach: fid =>
              lilaIn.round(LilaIn.RoundBye(fid))
            Behaviors.same

          case ClientOut.ChatSay(msg) =>
            state.player match
              case None =>
                req.user.foreach:
                  lilaIn round LilaIn.WatcherChatSay(state.room.room, _, msg)
              case Some(p) =>
                import Game.RoundExt.*
                def extMsg[A](id: A)(using bts: SameRuntime[A, String]) = req.user.map:
                  LilaIn.ChatSay(RoomId(bts(id)), _, msg)
                p.ext match
                  case None =>
                    lilaIn.round(LilaIn.PlayerChatSay(state.room.room, req.user.toLeft(p.color), msg))
                  case Some(InTour(id))  => extMsg(id) foreach lilaIn.tour
                  case Some(InSwiss(id)) => extMsg(id) foreach lilaIn.swiss
                  case Some(InSimul(id)) => extMsg(id) foreach lilaIn.simul
            Behaviors.same

          case ClientOut.ChatTimeout(suspect, reason, text) =>
            deps.req.user foreach { u =>
              def msg(id: RoomId) = LilaIn.ChatTimeout(id, u, suspect, reason, text)
              state.player flatMap { p =>
                p.tourId.map(_ into RoomId).map(msg).map(lilaIn.tour) orElse
                  p.simulId.map(_ into RoomId).map(msg).map(lilaIn.simul)
              } getOrElse lilaIn.round(msg(state.room.room))
            }
            Behaviors.same

          case ClientOut.RoundBerserk(ackId) =>
            if state.player.isDefined then
              req.user.foreach: u =>
                clientIn(ClientIn.Ack(ackId))
                lilaIn.round(LilaIn.RoundBerserk(gameId, u))
            Behaviors.same

          case ClientOut.RoundHold(mean, sd) =>
            fullId zip req.ip foreach { (fid, ip) =>
              lilaIn.round(LilaIn.RoundHold(fid, ip, mean, sd))
            }
            Behaviors.same

          case ClientOut.RoundSelfReport(name) =>
            fullId zip req.ip foreach { (fid, ip) =>
              lilaIn.round(LilaIn.RoundSelfReport(fid, ip, req.user, name))
            }
            Behaviors.same

          case ClientOut.PalantirPing =>
            deps.req.user map { Palantir.respondToPing(state.room.room, _) } foreach clientIn
            Behaviors.same

          // default receive (site)
          case msg: ClientOutSite =>
            val siteState = globalReceive(state.site, deps, ctx, msg)
            if siteState == state.site then Behaviors.same
            else apply(state.copy(site = siteState), deps)

          case _ =>
            Monitor.clientOutUnhandled("round").increment()
            Behaviors.same

      }
      .receiveSignal { case (ctx, PostStop) =>
        onStop(state.site, deps, ctx)
        state.busChans foreach { Bus.unsubscribe(_, ctx.self) }
        deps.roundCrowd.disconnect(state.room.room, deps.req.user, state.player.map(_.color))
        Behaviors.same
      }
