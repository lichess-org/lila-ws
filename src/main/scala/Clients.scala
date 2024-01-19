package lila.ws

import org.apache.pekko.actor.typed.scaladsl.Behaviors

object Clients:

  enum Control:
    case Start(behavior: ClientBehavior, promise: Promise[Client])
    case Stop(client: Client)

  def behavior =
    Behaviors.receive[Control]: (ctx, msg) =>
      msg match
        case Control.Start(behavior, promise) =>
          promise success ctx.spawnAnonymous(behavior)
          Behaviors.same
        case Control.Stop(client) =>
          ctx.stop(client)
          Behaviors.same
