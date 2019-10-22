package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, Behavior }
import akka.stream.scaladsl._
import akka.stream.{ Materializer, OverflowStrategy }
import javax.inject._
import play.api.http.websocket._
import play.api.libs.streams.AkkaStreams
import play.api.mvc.RequestHeader
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import ipc._
import lila.ws.util.Util.{ reqName, flagOf, nowSeconds }

@Singleton
final class Server @Inject() (
    auth: Auth,
    mongo: Mongo,
    stream: Stream
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

  kamon.Kamon.init()

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
      user.fold(Future successful IsTroll(false))(mongo.isTroll) map { isTroll =>
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
      user.fold(Future successful IsTroll(false))(mongo.isTroll) map { isTroll =>
        actorFlow(req) { clientIn =>
          TourClientActor.start(RoomActor.State(RoomId(tour.id), isTroll), fromVersion) {
            ClientActor.Deps(clientIn, queues, ClientActor.Req(reqName(req), sri, None, user), bus)
          }
        }
      }
    } map asWebsocket(new RateLimit(
      maxCredits = 30,
      duration = 20.seconds,
      name = s"simul ${reqName(req)}"
    ))

  private def connectTo(req: RequestHeader, sri: Sri, flag: Option[Flag])(
    actor: ClientActor.Deps => Behavior[ClientMsg]
  ): Future[Flow[ClientOut, ClientIn, _]] =
    auth(req, flag) map { user =>
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
  ): Flow[ClientOut, ClientIn, _] = {

    import akka.actor.{ Status, Terminated, OneForOneStrategy, SupervisorStrategy }

    val (outQueue, publisher) = Source.queue[ClientIn](
      bufferSize = 8,
      overflowStrategy = OverflowStrategy.dropHead
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    Flow.fromSinkAndSource(
      Sink.actorRef(
        ref = system.actorOf(akka.actor.Props(new akka.actor.Actor {
          val flowActor: ActorRef[ClientMsg] = context.spawn(clientActor(outQueue), "flowActor")
          context.watch(flowActor)

          def receive = {
            case Status.Success(_) | Status.Failure(_) => flowActor ! ClientCtrl.Disconnect
            case Terminated(_) => context.stop(self)
            case msg: ClientOut => flowActor ! msg
          }

          override def supervisorStrategy = OneForOneStrategy() {
            case _ => SupervisorStrategy.Stop
          }
        })),
        onCompleteMessage = Status.Success(()),
        onFailureMessage = t => Status.Failure(t)
      ),
      Source.fromPublisher(publisher)
    )
  }
}
