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
    stream: Stream,
    config: play.api.Configuration
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  type WebsocketFlow = Flow[Message, Message, _]

  private val queues = stream.start

  private val bus = Bus(system)

  system.scheduler.scheduleWithFixedDelay(30.seconds, 7211.millis) { () =>
    bus publish Bus.msg(ClientCtrl.Broom(nowSeconds - 30), _.all)
  }

  if (config.get[String]("kamon.influxdb.hostname").nonEmpty) {
    play.api.Logger(getClass).info("Kamon is enabled")
    kamon.Kamon.loadModules()
  }

  def connectToSite(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    connectTo(req, sri, flag)(SiteClientActor.start) map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"site ${reqName(req)}"
    ))

  def connectToLobby(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    connectTo(req, sri, flag)(LobbyClientActor.start) map asWebsocket(new RateLimit(
      maxCredits = 30,
      duration = 30.seconds,
      name = s"lobby ${reqName(req)}"
    ))

  def connectToSimul(req: RequestHeader, simul: Simul, sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    auth(req, None) flatMap { user =>
      mongo.isTroll(user) flatMap { isTroll =>
        actorFlow(req) { clientIn =>
          SimulClientActor.start(RoomActor.State(RoomId(simul.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, None, user), bus)
          }
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 30,
      duration = 20.seconds,
      name = s"simul ${reqName(req)}"
    ))

  def connectToTour(req: RequestHeader, tour: Tour, sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    auth(req, None) flatMap { user =>
      mongo.isTroll(user) flatMap { isTroll =>
        actorFlow(req) { clientIn =>
          TourClientActor.start(RoomActor.State(RoomId(tour.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, None, user), bus)
          }
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 30,
      duration = 20.seconds,
      name = s"tour ${reqName(req)}"
    ))

  def connectToStudy(req: RequestHeader, study: Study, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn =>
        StudyClientActor.start(RoomActor.State(RoomId(study.id), isTroll), fromVersion) {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, None, user), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 15.seconds,
      name = s"study ${reqName(req)}"
    ))

  def connectToRoundWatch(req: RequestHeader, gameId: Game.Id, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn =>
        RoundClientActor.start(RoomActor.State(RoomId(gameId), isTroll), None, fromVersion) {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, None, user), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"round/watch ${reqName(req)}"
    ))

  def connectToRoundPlay(req: RequestHeader, fullId: Game.FullId, color: chess.Color, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn =>
        val player = RoundClientActor.Player(fullId.playerId, color)
        RoundClientActor.start(RoomActor.State(RoomId(fullId.gameId), isTroll), Some(player), fromVersion) {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, None, user), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"round/watch ${reqName(req)}"
    ))

  private def connectTo(req: RequestHeader, sri: Sri, flag: Option[Flag])(
    actor: ClientActor.Deps => Behavior[ClientMsg]
  ): Future[Flow[ClientOut, ClientIn, _]] =
    auth(req, flag) flatMap { user =>
      actorFlow(req) { clientIn =>
        actor {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, flag, user), bus)
        }
      }
    }

  private def asWebsocket(limiter: RateLimit)(flow: Flow[ClientOut, ClientIn, _]): WebsocketFlow =
    AkkaStreams.bypassWith[Message, ClientOut, Message](Flow[Message] collect {
      case TextMessage(text) if limiter(text) && text.size < 2000 => ClientOut.parse(text).fold(
        _ => Right(CloseMessage(Some(CloseCodes.Unacceptable), "Unable to parse json message")),
        Left.apply
      )
    })(flow map { out => TextMessage(out.write) })

  private def actorFlow(req: RequestHeader)(
    clientActor: SourceQueue[ClientIn] => Behavior[ClientMsg]
  ): Future[Flow[ClientOut, ClientIn, _]] = {

    val (outQueue, publisher) = Source.queue[ClientIn](
      bufferSize = 8,
      overflowStrategy = OverflowStrategy.dropHead
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    Spawner(clientActor(outQueue)) map { actor =>
      Flow.fromSinkAndSource(
        ActorSink.actorRef[ClientMsg](
          ref = actor,
          onCompleteMessage = ClientCtrl.Disconnect,
          onFailureMessage = _ => ClientCtrl.Disconnect
        ),
        Source.fromPublisher(publisher)
      )
    }
  }

  private val logger = Logger("Server")
}
