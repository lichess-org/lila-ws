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
import lila.ws.util.Util.{ userAgent, flagOf }

@Singleton
final class SiteServer @Inject() (
    auth: Auth,
    stream: Stream
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  type WebsocketFlow = Flow[Message, Message, _]

  private val queues = stream.start

  private val bus = Bus(system)

  def connect(req: RequestHeader, sri: Sri, flag: Option[Flag]): Future[WebsocketFlow] =
    auth(req) map { user =>
      actorFlow(req) { clientIn =>
        SiteClientActor.start {
          SiteClientActor.Deps(clientIn, queues, sri, flag, user, userAgent(req), req.remoteAddress, bus)
        }
      }
    } map limitAndTransform(new RateLimit(
      maxCredits = 30,
      duration = 15.seconds,
      name = s"IP: ${req.remoteAddress} UA: ${userAgent(req)}"
    ))

  private def limitAndTransform(limiter: RateLimit)(flow: Flow[ClientOut, ClientIn, _]): WebsocketFlow =
    AkkaStreams.bypassWith[Message, ClientOut, Message](Flow[Message] collect {
      case TextMessage(text) if limiter(text) => ClientOut.parse(text).fold(
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
      Sink.actorRef(system.actorOf(akka.actor.Props(new akka.actor.Actor {
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
      })), Status.Success(())),
      Source.fromPublisher(publisher)
    )
  }
}
