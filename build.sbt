inThisBuild(
  Seq(
    scalaVersion       := "3.6.2",
    versionScheme      := Some("early-semver"),
    version            := "3.3",
    semanticdbEnabled  := true, // for scalafix
    dockerBaseImage    := "openjdk:21",
    dockerUpdateLatest := true
  )
)

val os    = if (sys.props.get("os.name").exists(_.startsWith("Mac"))) "osx" else "linux"
val arch  = if (sys.props.get("os.arch").exists(_.startsWith("aarch64"))) "aarch-64" else "x86-64"
val arch_ = arch.replace("-", "_")

val pekkoVersion = "1.1.2"
val kamonVersion = "2.7.5"
val nettyVersion = "4.1.116.Final"
val chessVersion = "16.5.0"

lazy val `lila-ws` = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name         := "lila-ws",
    organization := "org.lichess",
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers += "lila-maven".at("https://raw.githubusercontent.com/ornicar/lila-maven/master"),
    libraryDependencies ++= Seq(
      ("org.reactivemongo" %% "reactivemongo" % "1.1.0-RC13")
        .exclude("org.scala-lang.modules", "scala-java8-compat_2.13"),
      "org.reactivemongo" % s"reactivemongo-shaded-native-$os-$arch" % "1.1.0-RC14",
      "io.lettuce"        % "lettuce-core"                           % "6.5.1.RELEASE",
      "io.netty"          % "netty-handler"                          % nettyVersion,
      "io.netty"          % "netty-codec-http"                       % nettyVersion,
      ("io.netty"         % s"netty-transport-native-epoll"          % nettyVersion)
        .classifier(s"linux-$arch_"),
      ("io.netty" % s"netty-transport-native-kqueue" % nettyVersion)
        .classifier(s"osx-$arch_"),
      "org.lichess"                %% "scalalib-lila"        % "11.5.0",
      "org.lichess"                %% "scalachess"           % chessVersion,
      "org.lichess"                %% "scalachess-play-json" % chessVersion,
      "org.apache.pekko"           %% "pekko-actor-typed"    % pekkoVersion,
      "com.typesafe.scala-logging" %% "scala-logging"        % "3.9.5",
      "com.github.blemale"         %% "scaffeine"            % "5.3.0" % "compile",
      "ch.qos.logback"              % "logback-classic"      % "1.5.12",
      "org.playframework"          %% "play-json"            % "3.0.4",
      "io.kamon"                   %% "kamon-core"           % kamonVersion,
      "io.kamon"                   %% "kamon-influxdb"       % kamonVersion,
      "io.kamon"                   %% "kamon-prometheus"     % kamonVersion,
      "io.kamon"                   %% "kamon-system-metrics" % kamonVersion,
      "com.softwaremill.macwire"   %% "macros"               % "2.6.4" % "provided",
      "com.roundeights"            %% "hasher"               % "1.3.1",
      "org.scalameta"              %% "munit"                % "1.0.3" % Test
    ),
    scalacOptions := Seq(
      "-encoding",
      "utf-8",
      "-rewrite",
      "-source:3.7",
      "-indent",
      "-explaintypes",
      "-feature",
      "-language:postfixOps",
      "-Xtarget:21",
      "-Wunused:all"
    ),
    javaOptions ++= Seq("-Xms32m", "-Xmx256m"),
    Docker / packageName      := "lichess-org/lila-ws",
    Docker / maintainer       := "lichess.org",
    Docker / dockerRepository := Some("ghcr.io"),
    Universal / javaOptions := Seq(
      "-J-Dconfig.override_with_env_vars=true"
    )
  )

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias(
  "check",
  "; scalafixAll --check ; scalafmtCheckAll"
)
