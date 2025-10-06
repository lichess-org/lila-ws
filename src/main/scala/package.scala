package lila.ws

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }

export scalalib.newtypes.*
export scalalib.zeros.*
export scalalib.extensions.*
export scalalib.time.*
export scala.concurrent.{ ExecutionContext as Executor, Future, Promise }
export scala.concurrent.duration.{ DurationInt, FiniteDuration }
export scala.concurrent.ExecutionContext.parasitic
export org.apache.pekko.actor.typed.Scheduler

type Emit[A] = Function[A, Unit]

type ClientSystem = ActorSystem[Clients.Control]
type ClientBehavior = Behavior[ipc.ClientMsg]
type Client = ActorRef[ipc.ClientMsg]
type ClientEmit = Emit[ipc.ClientIn]

val startedAtMillis = System.currentTimeMillis()
