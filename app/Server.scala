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
    config: play.api.Configuration,
    monitor: Monitor,
    lifecycle: play.api.inject.ApplicationLifecycle
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  type WebsocketFlow = Flow[Message, Message, _]

  private val streamQueues: Future[Stream.Queues] = stream.start

  private val bus = Bus(system)

  monitor.start

  system.scheduler.scheduleWithFixedDelay(30.seconds, 7211.millis) { () =>
    bus publish Bus.msg(ClientCtrl.Broom(nowSeconds - 30), _.all)
  }
  streamQueues foreach { queues =>
    system.scheduler.scheduleWithFixedDelay(5.seconds, 1811.millis) { () =>
      queues(_.site, LilaIn.Connections(sm.CountSM.get))
    }
    Spawner(RoundCrowd.botListener(queues(_.roundCrowd, _))) foreach {
      bus.on(_, Bus.channel.roundBot)
    }
  }

  def connectToSite(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    auth(req, flag) flatMap { user =>
      actorFlow(req, bufferSize = 8) { clientIn => queues =>
        SiteClientActor.start {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user, flag), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"site ${reqName(req)}"
    ))

  def connectToLobby(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    auth(req, flag) flatMap { user =>
      actorFlow(req, bufferSize = 8) { clientIn => queues =>
        LobbyClientActor.start {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user, flag), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 30,
      duration = 30.seconds,
      name = s"lobby ${reqName(req)}"
    ))

  def connectToSimul(req: RequestHeader, simul: Simul, sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    auth(req, None) flatMap { user =>
      mongo.isTroll(user) flatMap { isTroll =>
        actorFlow(req) { clientIn => queues =>
          SimulClientActor.start(RoomActor.State(RoomId(simul.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user), bus)
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
        actorFlow(req) { clientIn => queues =>
          TourClientActor.start(RoomActor.State(RoomId(tour.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user), bus)
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
      actorFlow(req) { clientIn => queues =>
        StudyClientActor.start(RoomActor.State(RoomId(study.id), isTroll), fromVersion) {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 15.seconds,
      name = s"study ${reqName(req)}"
    ))

  def connectToRoundWatch(req: RequestHeader, gameId: Game.Id, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion], userTv: Option[UserTv]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn => queues =>
        userTv foreach { tv =>
          queues(_.round, LilaIn.UserTv(gameId, tv.value))
        }
        RoundClientActor.start(RoomActor.State(RoomId(gameId), isTroll), None, userTv, fromVersion) {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"round/watch ${reqName(req)}"
    ))

  def connectToRoundPlay(req: RequestHeader, fullId: Game.FullId, player: Player, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    mongo.isTroll(user) flatMap { isTroll =>
      actorFlow(req) { clientIn => queues =>
        RoundClientActor.start(RoomActor.State(RoomId(fullId.gameId), isTroll), Some(player), None, fromVersion) {
          ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user), bus)
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 20.seconds,
      name = s"round/watch ${reqName(req)}"
    ))

  def connectToChallenge(req: RequestHeader, challengeId: Challenge.Id, owner: Boolean, user: Option[User], sri: Sri, fromVersion: Option[SocketVersion]): Future[WebsocketFlow] =
    actorFlow(req) { clientIn => queues =>
      ChallengeClientActor.start(RoomActor.State(RoomId(challengeId), IsTroll(false)), owner, fromVersion) {
        ClientActor.Deps(clientIn, queues, ClientActor.Req(req, sri, user), bus)
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 50,
      duration = 30.seconds,
      name = s"challenge ${reqName(req)}"
    ))

  private def asWebsocket(limiter: RateLimit)(flow: Flow[ClientOut, ClientIn, _]): WebsocketFlow =
    AkkaStreams.bypassWith[Message, ClientOut, Message](Flow[Message] collect {
      case TextMessage(text) if limiter(text) && text.size < 2000 => ClientOut.parse(text).fold(
        _ => Right(CloseMessage(Some(CloseCodes.Unacceptable), "Unable to parse json message")),
        Left.apply
      )
    })(flow map { out => TextMessage(out.write) })

  private def actorFlow(req: RequestHeader, bufferSize: Int = roomClientInBufferSize)(
    clientActor: SourceQueue[ClientIn] => Stream.Queues => Behavior[ClientMsg]
  ): Future[Flow[ClientOut, ClientIn, _]] = streamQueues flatMap { queues =>

    val (outQueue, publisher) = Source.queue[ClientIn](
      bufferSize = bufferSize,
      overflowStrategy = OverflowStrategy.dropHead
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    Spawner(clientActor(outQueue)(queues)) map { actor =>
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
  private val roomClientInBufferSize = 32

  private val logger = Logger("Server")
}
