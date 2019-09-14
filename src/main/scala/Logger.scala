package lila.ws

final class Logger(root: String) {

  def info(msg: String) = log(s"[INFO] $msg")
  def warn(msg: String) = log(s"[WARN] $msg")

  def log(msg: String) = println(msg)
}
