package lila.ws

import scala.concurrent.ExecutionContext

final class FriendList(graph: SocialGraph)(implicit ec: ExecutionContext) {

  def start(userId: User.ID, emit: Emit[ipc.ClientIn]): Unit = {
    println("start")
    graph.followed(userId) foreach { users =>
      println(users)
      emit(ipc.ClientIn.FriendList(users))
    }
  }
}
