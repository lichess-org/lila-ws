name := "lila-ws"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala).disablePlugins(PlayFilters)

/* val akkaVersion = "2.6.0-M2" */

scalaVersion := "2.13.0"

libraryDependencies += guice
libraryDependencies += "org.reactivemongo" %% "reactivemongo" % "0.18.4"
libraryDependencies += "io.lettuce" % "lettuce-core" % "5.1.8.RELEASE"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)
