package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorRef, Behavior }
import akka.stream.scaladsl._
import akka.stream.{ Materializer, OverflowStrategy }
import javax.inject._
import play.api.mvc.RequestHeader
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class SiteServer @Inject() (
    auth: Auth,
    stream: Stream
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  private val queues = stream.start

  private val bus = Bus(system)

  def connect(req: RequestHeader, sri: Sri, flag: Option[Flag]) =
    auth(req) map { user =>
      actorFlow(req, sri) { clientIn =>
        SiteClientActor.start {
          SiteClientActor.Deps(clientIn, queues, sri, flag, user, userAgent(req), req.remoteAddress, bus)
        }
      }
    }

  private def actorFlow(req: RequestHeader, sri: Sri)(
    behaviour: akka.actor.ActorRef => Behavior[ClientMsg]
  )(implicit factory: akka.actor.ActorRefFactory, mat: Materializer): Flow[ClientOut, ClientIn, _] = {

    import akka.actor.{ Status, Terminated, OneForOneStrategy, SupervisorStrategy }

    val limiter = new RateLimit(
      maxCredits = 30,
      duration = 15.seconds,
      name = s"IP: ${req.remoteAddress} UA: ${userAgent(req)}"
    )
    val limiterFlow = Flow[ClientOut].collect {
      case msg: ClientOut if limiter(msg.toString) => msg
    }

    val (outActor, publisher) = Source.actorRef[ClientIn](
      bufferSize = 4,
      overflowStrategy = OverflowStrategy.dropHead
    ).toMat(Sink.asPublisher(false))(Keep.both).run()

    val actorSink: Sink[ClientOut, _] = akka.stream.typed.scaladsl.ActorSink.actorRef(
      system.spawn(behaviour(outActor), s"client:${sri.value}"),
      onCompleteMessage = ClientCtrl.Disconnect,
      onFailureMessage = _ => ClientCtrl.Disconnect
    )

    Flow.fromSinkAndSource(
      limiterFlow to actorSink,
      Source.fromPublisher(publisher)
    )
  }

  private def userAgent(req: RequestHeader) = req.headers.get("User-Agent") getOrElse "?"
}
