package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, Behavior }
import akka.stream.scaladsl._
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.{ Materializer, OverflowStrategy }
import javax.inject._
import play.api.http.websocket._
import play.api.libs.streams.AkkaStreams
import play.api.Logger
import play.api.mvc.RequestHeader
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import ipc._
import lila.ws.util.Util.{ reqName, flagOf, nowSeconds }

@Singleton
final class Server @Inject() (
    auth: Auth,
    mongo: Mongo,
    lila: Lila,
    lilaHandler: LilaHandler,
    services: Services,
    monitor: Monitor,
    lifecycle: play.api.inject.ApplicationLifecycle
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  type WebsocketFlow = Flow[Message, Message, _]

  monitor.start
  lila registerHandlers lilaHandler.handlers

  system.scheduler.schedule(30.seconds, 7211.millis) {
    Bus.publish(_.all, ClientCtrl.Broom(nowSeconds - 30))
  }
  system.scheduler.schedule(5.seconds, 1811.millis) {
    val connections = Connections.get
    lila.emit.site(LilaIn.Connections(connections))
    Monitor.connection.current update connections
  }

  def connectToSite(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    auth(req, flag) flatMap { user =>
      actorFlow(req, bufferSize = 8) { clientIn =>
        SiteClientActor.start {
          ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user, flag), services)
        }
      }
    } map asWebsocket("site", new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"site ${reqName(req)}"
    ))

  def connectToLobby(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    auth(req, flag) flatMap { user =>
      actorFlow(req, bufferSize = 8) { clientIn =>
        LobbyClientActor.start {
          ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user, flag), services)
        }
      }
    } map asWebsocket("lobby", new RateLimit(
      maxCredits = 30,
      duration = 30.seconds,
      name = s"lobby ${reqName(req)}"
    ))

  def connectToSimul(req: RequestHeader, simul: Simul, sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    auth(req, None) flatMap { user =>
      mongo.isTroll(user) flatMap { isTroll =>
        actorFlow(req) { clientIn =>
          SimulClientActor.start(RoomActor.State(RoomId(simul.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user), services)
          }
        }
      }
    } map asWebsocket("simul", new RateLimit(
      maxCredits = 30,
      duration = 20.seconds,
      name = s"simul ${reqName(req)}"
    ))

  def connectToTour(req: RequestHeader, tour: Tour, sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    auth(req, None) flatMap { user =>
      mongo.isTroll(user) flatMap { isTroll =>
        actorFlow(req) { clientIn =>
          TourClientActor.start(RoomActor.State(RoomId(tour.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user), services)
          }
        }
      }
    } map asWebsocket("tour", new RateLimit(
      maxCredits = 30,
      duration = 20.seconds,
      name = s"tour ${reqName(req)}"
    ))

  def connectToStudy(req: RequestHeader, study: Study, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn =>
        StudyClientActor.start(RoomActor.State(RoomId(study.id), isTroll), fromVersion) {
          ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user), services)
        }
      }
    } map asWebsocket("study", new RateLimit(
      maxCredits = 50,
      duration = 15.seconds,
      name = s"study ${reqName(req)}"
    ))

  def connectToRoundWatch(req: RequestHeader, gameId: Game.Id, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion], userTv: Option[UserTv]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn =>
        userTv foreach { tv =>
          lila.emit.round(LilaIn.UserTv(gameId, tv.value))
        }
        RoundClientActor.start(RoomActor.State(RoomId(gameId), isTroll), None, userTv, fromVersion) {
          ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user), services)
        }
      }
    } map asWebsocket("round-watch", new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"round/watch ${reqName(req)}"
    ))

  def connectToRoundPlay(req: RequestHeader, fullId: Game.FullId, player: Game.RoundPlayer, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn =>
        RoundClientActor.start(RoomActor.State(RoomId(fullId.gameId), isTroll), Some(player), None, fromVersion) {
          ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user), services)
        }
      }
    } map asWebsocket("round-play", new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"round/play ${reqName(req)}"
    ))

  def connectToChallenge(req: RequestHeader, challengeId: Challenge.Id, owner: Boolean, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    actorFlow(req) { clientIn =>
      ChallengeClientActor.start(RoomActor.State(RoomId(challengeId), IsTroll(false)), owner, fromVersion) {
        ClientActor.Deps(clientIn, ClientActor.Req(req, sri, user), services)
      }
    } map asWebsocket("challenge", new RateLimit(
      maxCredits = 50,
      duration = 30.seconds,
      name = s"challenge ${reqName(req)}"
    ))

  private def asWebsocket(monitorName: String, limiter: RateLimit)(flow: Flow[ClientOut, ClientIn, _]): WebsocketFlow = {
    Monitor.connection open monitorName
    AkkaStreams.bypassWith[Message, ClientOut, Message](Flow[Message] collect {
      case TextMessage(text) if limiter(text) && text.size < 2000 => ClientOut.parse(text).fold(
        _ => Right(CloseMessage(Some(CloseCodes.Unacceptable), "Unable to parse json message")),
        Left.apply
      )
    })(flow map { out => TextMessage(out.write) })
  }

  private def actorFlow(req: RequestHeader, bufferSize: Int = roomClientInBufferSize)(
    clientActor: Emit[ClientIn] => Behavior[ClientMsg]
  ): Future[Flow[ClientOut, ClientIn, _]] = {

    val (outQueue, publisher) = Source.queue[ClientIn](
      bufferSize = bufferSize,
      overflowStrategy = OverflowStrategy.dropHead
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    Spawner(clientActor(outQueue.offer)) map { actor =>
      Flow.fromSinkAndSource(
        ActorSink.actorRef[ClientMsg](
          ref = actor,
          onCompleteMessage = ClientCtrl.Disconnect,
          onFailureMessage = e => {
            // println(s"failure $req $e")
            ClientCtrl.Disconnect
          }
        ),
        Source.fromPublisher(publisher)
      // .wireTap(x => println(s"  $x"))
      // .delay(900.millis) // quick way to simulate network lag!
      )
    }
  }

  // when a client connects from an old socket version,
  // catch-up messages are buffered until sent
  private val roomClientInBufferSize = 22

  private val logger = Logger("Server")

  lifecycle addStopHook { () => Future successful lila.closeAll }
}
