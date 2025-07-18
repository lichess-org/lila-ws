package lila.ws

object Impersonations:

  private var all = Map.empty[User.ModId, User.Id]

  def start(mod: User.ModId, user: User.Id): Unit =
    all = all + (mod -> user)

  def stop(mod: User.ModId): Unit =
    all = all - mod

  def get(modId: User.ModId): Option[User.Id] = all.get(modId)

  def reset() =
    all = Map.empty
