package lila.ws

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import io.lettuce.core.RedisURI
import javax.inject._
import play.api.Configuration
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class Stream @Inject() (
    config: Configuration,
    graph: Graph
)(implicit
    ec: ExecutionContext,
    system: ActorSystem,
    mat: akka.stream.Materializer
) {

  val lila = new Lila(redisUri = RedisURI.create(config.get[String]("redis.uri")))

  def start: Stream.Queues = {

    val (siteInit, siteSink) = lila.pubsub("site-in", "site-out") {
      case out: SiteOut => out
    }
    val (lobbyInit, lobbySink) = lila.pubsub("lobby-in", "lobby-out") {
      case out: LobbyOut => out
    }

    graph.main(siteSink, lobbySink).run() match {
      case (siteOut, lobbyOut, queues) =>
        siteInit(siteOut, List(LilaIn.DisconnectAll))
        lobbyInit(lobbyOut, List(LilaIn.DisconnectAll))
        queues
    }
  }
}

object Stream {

  case class Queues(
      site: SourceQueue[LilaIn],
      lobby: SourceQueue[LilaIn],
      lag: SourceQueue[LagSM.Input],
      fen: SourceQueue[FenSM.Input],
      count: SourceQueue[CountSM.Input],
      user: SourceQueue[UserSM.Input]
  ) {
    def apply[A](select: Queues => SourceQueue[A], msg: A): Unit =
      select(this) offer msg
  }
}
