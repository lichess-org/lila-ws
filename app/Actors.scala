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

      case move: LilaOut.Move =>
        fenActor ! move

      case LilaOut.Mlat(millis) =>
        lagActor ! LagActor.Publish
        countActor ! CountActor.Publish
        bus.publish(ClientIn.Mlat(millis), _.mlat)

      case LilaOut.TellFlag(flag, json) =>
        bus.publish(ClientIn.AnyJson(json), _ flag flag)

      case LilaOut.TellUser(user, json) =>
        userActor ! UserActor.Tell(user, ClientIn.AnyJson(json))

      case LilaOut.TellSri(sri, json) =>
        bus.publish(ClientIn.AnyJson(json), _ sri sri)

      case LilaOut.TellAll(json) =>
        bus.publish(ClientIn.AnyJson(json), _.all)

      case LilaOut.DisconnectUser(user) =>
        userActor ! UserActor.Tell(user, PoisonPill)
    }
  )), "site")

  val countActor: ActorRef = system.actorOf(Props(new CountActor(lilaSite.!)), "count")

  val lagActor: ActorRef = system.actorOf(LagActor.props(lilaSite.!), "lag")

  val fenActor: ActorRef = system.actorOf(FenActor.props(lilaSite.!), "fen")

  val userActor: ActorRef = system.actorOf(UserActor.props(lilaSite.!), "user")
}
