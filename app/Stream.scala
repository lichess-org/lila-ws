package lila.ws

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import io.lettuce.core.RedisURI
import javax.inject._
import play.api.Configuration
import scala.concurrent.{ ExecutionContext, Future }

import ipc._
import sm._

@Singleton
final class Stream @Inject() (config: Configuration, crowdJson: CrowdJson)(implicit
    ec: ExecutionContext,
    mongo: Mongo,
    system: ActorSystem,
    mat: akka.stream.Materializer
) {

  private val lila = new Lila(redisUri = RedisURI.create(config.get[String]("redis.uri")))

  def start: Stream.Queues = {

    val (siteInit, siteSink) = lila.pubsub("site-in", "site-out") {
      case out: SiteOut => out
    }
    val (lobbyInit, lobbySink) = lila.pubsub("lobby-in", "lobby-out") {
      case out: LobbyOut => out
    }
    val (simulInit, simulSink) = lila.pubsub("simul-in", "simul-out") {
      case out: SimulOut => out
    }
    val (tourInit, tourSink) = lila.pubsub("tour-in", "tour-out") {
      case out: TourOut => out
    }
    val (studyInit, studySink) = lila.pubsub("study-in", "study-out") {
      case out: StudyOut => out
    }

    val (siteOut, lobbyOut, simulOut, tourOut, studyOut, queues) = Graph(siteSink, lobbySink, simulSink, tourSink, studySink, mongo, crowdJson).run()

    siteInit(siteOut, List(LilaIn.DisconnectAll))
    lobbyInit(lobbyOut, List(LilaIn.DisconnectAll))
    simulInit(simulOut, List(LilaIn.DisconnectAll))
    tourInit(tourOut, List(LilaIn.DisconnectAll))
    studyInit(studyOut, List(LilaIn.DisconnectAll))

    queues
  }
}

object Stream {

  case class Queues(
      notified: SourceQueue[LilaIn.Notified],
      friends: SourceQueue[LilaIn.Friends],
      site: SourceQueue[LilaIn.Site],
      lobby: SourceQueue[LilaIn.Lobby],
      simul: SourceQueue[LilaIn.Simul],
      tour: SourceQueue[LilaIn.Tour],
      study: SourceQueue[LilaIn.Study],
      round: SourceQueue[LilaIn.Round],
      connect: SourceQueue[LilaIn.ConnectSri],
      disconnect: SourceQueue[LilaIn.DisconnectSri],
      lag: SourceQueue[UserLag],
      fen: SourceQueue[FenSM.Input],
      count: SourceQueue[CountSM.Input],
      user: SourceQueue[UserSM.Input],
      crowd: SourceQueue[RoomCrowd.Input],
      studyDoor: SourceQueue[ThroughStudyDoor]
  ) {
    def apply[A](select: Queues => SourceQueue[A], msg: A): Unit =
      select(this) offer msg
  }
}
