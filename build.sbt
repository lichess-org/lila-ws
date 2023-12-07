name := "lila-ws"

version := "3.1"

lazy val `lila-ws` = (project in file("."))
  .enablePlugins(JavaAppPackaging)

resolvers += ("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")

val os    = if (sys.props.get("os.name").exists(_.startsWith("Mac"))) "osx" else "linux"
val arch  = if (sys.props.get("os.arch").exists(_.startsWith("aarch64"))) "aarch-64" else "x86-64"
val arch_ = arch.replace("-", "_")

val pekkoVersion = "1.0.2"
val kamonVersion = "2.7.0"
val nettyVersion = "4.1.101.Final"

scalaVersion := "3.3.1"

libraryDependencies += "org.reactivemongo" %% "reactivemongo" % "1.1.0-RC11" exclude ("org.scala-lang.modules", "scala-java8-compat_2.13")
libraryDependencies += "org.reactivemongo" % s"reactivemongo-shaded-native-$os-$arch" % "1.1.0-RC11"
libraryDependencies += "io.lettuce"        % "lettuce-core"                           % "6.3.0.RELEASE"
libraryDependencies += "io.netty"          % "netty-handler"                          % nettyVersion
libraryDependencies += "io.netty"          % "netty-codec-http"                       % nettyVersion
libraryDependencies += "io.netty" % s"netty-transport-native-epoll"  % nettyVersion classifier s"linux-$arch_"
libraryDependencies += "io.netty" % s"netty-transport-native-kqueue" % nettyVersion classifier s"osx-$arch_"
libraryDependencies += "com.github.ornicar" %% "scalalib"          % "9.5.5"
libraryDependencies += "org.lichess"        %% "scalachess"        % "15.6.11"
libraryDependencies += "org.apache.pekko"   %% "pekko-actor-typed" % pekkoVersion

// libraryDependencies += "com.typesafe.akka"          %% "akka-slf4j"       % akkaVersion
// libraryDependencies += "org.apache.pekko"           % "pekko-slf4j_3"     % pekkoVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"        % "3.9.5"
libraryDependencies += "com.github.blemale"         %% "scaffeine"            % "5.2.1"     % "compile"
libraryDependencies += "ch.qos.logback"              % "logback-classic"      % "1.4.14"
libraryDependencies += "org.playframework"          %% "play-json"            % "3.0.1"
libraryDependencies += "io.kamon"                   %% "kamon-core"           % kamonVersion
libraryDependencies += "io.kamon"                   %% "kamon-influxdb"       % kamonVersion
libraryDependencies += "io.kamon"                   %% "kamon-prometheus"     % kamonVersion
libraryDependencies += "io.kamon"                   %% "kamon-system-metrics" % kamonVersion
libraryDependencies += "com.softwaremill.macwire"   %% "macros"               % "2.5.9"     % "provided"
libraryDependencies += "com.roundeights"            %% "hasher"               % "1.3.1"
libraryDependencies += "org.scalameta"              %% "munit"                % "1.0.0-M10" % Test

resolvers ++= Resolver.sonatypeOssRepos("snapshots")
resolvers += "lila-maven" at "https://raw.githubusercontent.com/ornicar/lila-maven/master"

scalacOptions := Seq(
  "-encoding",
  "utf-8",
  "-rewrite",
  "-source:future-migration",
  "-indent",
  "-explaintypes",
  "-feature",
  "-language:postfixOps",
  "-Xtarget:21",
  "-Wunused:all"
  /* "-Wunused:nowarn" */

  // Warnings as errors!
  // "-Xfatal-warnings",
)

javaOptions ++= Seq("-Xms32m", "-Xmx256m")

Compile / doc / sources := Seq.empty

Compile / packageDoc / publishArtifact := false

/* scalafmtOnCompile := true */
