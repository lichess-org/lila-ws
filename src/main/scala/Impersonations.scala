package lila.ws

object Impersonations:

  private type ModId = User.Id

  private var all = Map.empty[ModId, User.Id]

  def apply(user: User.Id, by: Option[User.Id]): Unit =
    by match
      case Some(modId) => all = all + (modId -> user)
      case None =>
        all collectFirst {
          case (m, u) if u == user => m
        } foreach { modId => all = all - modId }

  def get(modId: ModId): Option[User.Id] = all get modId

  def reset() =
    all = Map.empty
