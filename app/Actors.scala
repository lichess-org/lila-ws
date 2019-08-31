package lila.ws

import akka.actor._
import io.lettuce.core.RedisURI
import javax.inject._
import play.api.Configuration

import ipc._

@Singleton
final class Actors @Inject() (
    config: Configuration,
    system: ActorSystem
) {

  private val bus = Bus(system)

  val lilaSite: ActorRef = system.actorOf(Props(new LilaActor(
    redisUri = RedisURI.create(config.get[String]("redis.uri")),
    chanIn = "site-in",
    chanOut = "site-out",
    onReceive = {

      case move: LilaOut.Move => fen ! move

      case LilaOut.Mlat(millis) =>
        lag ! LagActor.Publish
        count ! CountActor.Publish
        bus.publish(ClientIn.Mlat(millis), _.mlat)

      case LilaOut.TellFlag(flag, json) =>
        bus.publish(ClientIn.AnyJson(json), _ flag flag)

      case LilaOut.TellAll(json) =>
        bus.publish(ClientIn.AnyJson(json), _.all)
    }
  )), "site")
  val count: ActorRef = system.actorOf(Props(new CountActor(lilaSite.!)), "count")
  val lag: ActorRef = system.actorOf(LagActor.props(lilaSite.!), "lag")
  val fen: ActorRef = system.actorOf(FenActor.props(lilaSite.!), "fen")
}
