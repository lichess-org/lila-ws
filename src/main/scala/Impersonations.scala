package lila.ws

object Impersonations {

  private type ModId = User.ID

  private var all = Map.empty[ModId, User.ID]

  def apply(user: User.ID, by: Option[User.ID]): Unit =
    by match {
      case Some(modId) => all = all + (modId -> user)
      case None =>
        all collectFirst {
          case (m, u) if u == user => m
        } foreach { modId => all = all - modId }
    }

  def get(modId: ModId): Option[User.ID] = all get modId

  def reset() = {
    all = Map.empty
  }
}
