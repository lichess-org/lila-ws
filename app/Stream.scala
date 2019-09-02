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

    val (init, sink) = lila.pubsub("site-in", "site-out")

    graph.main(sink).run() match {
      case (lilaOut, queues) =>
        init(lilaOut, List(LilaIn.DisconnectAll))
        queues
    }
  }
}

object Stream {

  case class Queues(
      lila: SourceQueue[LilaIn],
      lag: SourceQueue[LagSM.Input],
      fen: SourceQueue[FenSM.Input],
      count: SourceQueue[CountSM.Input],
      user: SourceQueue[UserSM.Input]
  ) {
    def apply[A](select: Queues => SourceQueue[A], msg: A): Unit =
      select(this) offer msg
  }
}
