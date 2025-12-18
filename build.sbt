inThisBuild(
  Seq(
    scalaVersion := "3.7.4",
    versionScheme := Some("early-semver"),
    version := "3.3",
    dockerBaseImage := "eclipse-temurin:25-jdk-noble",
    dockerUpdateLatest := true,
    semanticdbEnabled := true // for scalafix
  )
)

val os = if (sys.props.get("os.name").exists(_.startsWith("Mac"))) "osx" else "linux"
val arch = if (sys.props.get("os.arch").exists(_.startsWith("aarch64"))) "aarch-64" else "x86-64"
val arch_ = arch.replace("-", "_")

val pekkoVersion = "1.3.0"
val kamonVersion = "2.8.0"
val nettyVersion = "4.2.9.Final"
val chessVersion = "17.14.1"

lazy val `lila-ws` = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(
    name := "lila-ws",
    organization := "org.lichess",
    resolvers += Resolver.sonatypeCentralSnapshots,
    resolvers += "jitpack".at("https://jitpack.io"),
    resolvers += "lila-maven".at("https://raw.githubusercontent.com/ornicar/lila-maven/master"),
    libraryDependencies ++= Seq(
      ("org.reactivemongo" %% "reactivemongo" % "1.1.0-RC19")
        .exclude("org.scala-lang.modules", "scala-java8-compat_2.13"),
      "org.reactivemongo" % s"reactivemongo-shaded-native-$os-$arch" % "1.1.0-RC19",
      "io.lettuce" % "lettuce-core" % "7.2.1.RELEASE",
      "io.netty" % "netty-handler" % nettyVersion,
      "io.netty" % "netty-codec-http" % nettyVersion,
      ("io.netty" % s"netty-transport-native-epoll" % nettyVersion)
        .classifier(s"linux-$arch_"),
      ("io.netty" % s"netty-transport-native-kqueue" % nettyVersion)
        .classifier(s"osx-$arch_"),
      "com.github.lichess-org.scalalib" %% "scalalib-lila" % "11.9.5",
      "com.github.lichess-org.scalachess" %% "scalachess" % chessVersion,
      "com.github.lichess-org.scalachess" %% "scalachess-play-json" % chessVersion,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
      "com.github.blemale" %% "scaffeine" % "5.3.0" % "compile",
      "com.github.ben-manes.caffeine" % "caffeine" % "3.2.3" % "compile",
      "ch.qos.logback" % "logback-classic" % "1.5.21",
      "org.playframework" %% "play-json" % "3.0.6",
      "io.kamon" %% "kamon-core" % kamonVersion,
      "io.kamon" %% "kamon-influxdb" % kamonVersion,
      "io.kamon" %% "kamon-prometheus" % kamonVersion,
      "io.kamon" %% "kamon-system-metrics" % kamonVersion,
      "com.softwaremill.macwire" %% "macros" % "2.6.7" % "provided",
      "com.roundeights" %% "hasher" % "1.3.1",
      "org.scalameta" %% "munit" % "1.2.1" % Test
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
    javacOptions ++= Seq("--release", "21"),
    Docker / packageName := "lichess-org/lila-ws",
    Docker / maintainer := "lichess.org",
    Docker / dockerRepository := Some("ghcr.io"),
    Universal / javaOptions := Seq(
      "-J-Dconfig.override_with_env_vars=true"
    ),
    Compile / doc / sources := Seq.empty,
    buildInfoPackage := "lila.ws",
    buildInfoKeys := Seq[BuildInfoKey](
      BuildInfoKey.map(git.gitHeadCommit) { case (k, v) => k -> v.getOrElse("unknown") }
    )
  )

addCommandAlias("prepare", "scalafixAll; scalafmtAll")
addCommandAlias(
  "check",
  "; scalafixAll --check ; scalafmtCheckAll"
)
