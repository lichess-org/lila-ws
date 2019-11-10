package lila.ws
package sm

object ImpersonateSM {

  private type ModId = User.ID

  private var all = Map.empty[ModId, User.ID]

  def apply(i: ipc.LilaOut.Impersonate): Unit = i.by match {
    case Some(modId) => all = all + (modId -> i.user)
    case None => all collectFirst {
      case (m, u) if u == i.user => m
    } foreach { modId =>
      all = all - modId
    }
  }

  def get(modId: ModId): Option[User.ID] = all get modId
}
