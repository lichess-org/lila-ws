Handle incoming websocket traffic for [lichess.org](https://lichess.org).

```
lila <-> redis <-> lila-ws <-> websocket <-> client
```

Start:
```
sbt
```

Run server and reload on file change:
```
~reStart
```

Start with custom config file:
```
sbt -Dconfig.file=/path/to/my.conf
```

Custom config file example:
```
include "application"
bind.host = "localhost"
bind.port = 9664
mongo.uri = "mongodb://localhost:27017/lichess"
redis.uri = "redis://127.0.0.1"
csrf.origin = "http://localhost"
```

systemd service file example:
```
[Unit]
Description=lila-ws
After=network.target

[Service]
Environment="JAVA_OPTS=-Xms128m -Xmx512m"
ExecStart=/home/lila-ws/bin/lila-ws -Dbind.port=9664
WorkingDirectory=/home/lila-ws
StandardError=null
PIDFile=/home/lila-ws/RUNNING_PID

[Install]
WantedBy=multi-user.target
```
