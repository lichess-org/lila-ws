package lila.ws

import ipc.ClientIn.LobbyPong

object LobbyPongStore {

  private var pong = LobbyPong(0, 0)

  def get = pong

  def update(f: LobbyPong => LobbyPong): Unit = {
    pong = f(pong)
  }
}
