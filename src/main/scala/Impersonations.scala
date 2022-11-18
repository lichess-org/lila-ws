package lila.ws

object Impersonations:

  private type ModId = UserId

  private var all = Map.empty[ModId, UserId]

  def apply(user: UserId, by: Option[UserId]): Unit =
    by match
      case Some(modId) => all = all + (modId -> user)
      case None =>
        all collectFirst {
          case (m, u) if u == user => m
        } foreach { modId => all = all - modId }

  def get(modId: ModId): Option[UserId] = all get modId

  def reset() =
    all = Map.empty
