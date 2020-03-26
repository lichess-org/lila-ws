package lila.ws

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import java.util.concurrent.Executor
import org.specs2.mutable._

class SocialGraphTest extends Specification {
  private def loadFollowed(id: User.ID): Future[List[UserRecord]] = Future successful {
    id.split("_").map(f => UserRecord(f, f.toUpperCase)).toList
  }

  "social graph" >> {
    implicit val ec = ExecutionContext.fromExecutor(new Executor {
      def execute(runnable: Runnable): Unit = runnable.run()
    })

    val graph      = new SocialGraph(loadFollowed, 3 /* 2^3 slots*/, 0 /* 2^0 locks */ )
    val abFollowed = Await.result(graph.followed("a_b"), 2 seconds)
    abFollowed must_== List(UserInfo("a", "A", None), UserInfo("b", "B", None))

    val aOnline = graph.tell("a", UserMeta(online = true))
    aOnline must_== List("a_b")

    val cOnline = graph.tell("c", UserMeta(online = true))
    cOnline must_== Nil

    val abcFollowed = Await.result(graph.followed("a_b_c"), 2 seconds)
    abcFollowed must_== List(
      UserInfo("a", "A", Some(UserMeta(online = true))),
      UserInfo("b", "B", None),
      UserInfo("c", "C", Some(UserMeta(online = true)))
    )

    val aOffline = graph.tell("a", UserMeta(online = false))
    aOffline.toSet must_== Set("a_b", "a_b_c")

    graph.unfollow("a_b_c", "c")

    val abcFollowedAgain = Await.result(graph.followed("a_b_c"), 2 seconds)
    abcFollowedAgain must_== List(
      UserInfo("a", "A", Some(UserMeta(online = false))),
      UserInfo("b", "B", None)
    )

    graph.follow("c", UserRecord("b", "B"))
    graph.follow("a", UserRecord("c", "C"))
    graph.follow("a", UserRecord("a_b", "A_B"))
    graph.follow("a_b", UserRecord("c", "C"))

    val bOnline = graph.tell("b", UserMeta(online = true))
    bOnline.toSet must_== Set("a_b", "a_b_c", "c")
  }
}
