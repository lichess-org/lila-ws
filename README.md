# Lila websocket

Handle incoming websocket traffic for [lichess.org](https://lichess.org).

```
lila <-> redis <-> lila-ws <-> websocket <-> client
```

Start:
```
sbt
~reStart
```

Start with custom config file:
```
sbt -Dconfig.file=/path/to/my.conf
```

Custom config file example:
```
include "application"
http.port = 8080
mongo.uri = "mongodb://localhost:27017/lichess"
redis.uri = "redis://127.0.0.1"
```

Trusts `X-Forwarded-For`. Use only behind a reverse proxy!

### Code formatting

Please `sbt prepare` before you submit a PR. This will reformats and do some auto refactoring the code.
