import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.DefaultBuildSettings

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "3.5.0"

lazy val microservice = Project("api-hub-applications", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    // https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
    // suppress warnings in generated routes files
    scalacOptions += "-Wconf:src=routes/.*:s",
    PlayKeys.devSettings ++= Seq(
      "play.http.router" -> "testOnlyDoNotUseInAppConf.Routes"
    ),
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.apihubapplications.models.application.EnvironmentName",
      "uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestStatus"
    )
  )
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(CodeCoverageSettings.settings*)
  .settings(
    Test / unmanagedSourceDirectories += baseDirectory.value / "test-common"
  )
  .settings(scalacOptions ++= Seq("-deprecation", "-feature"))
  .settings(scalacOptions := scalacOptions.value.diff(Seq("-Wunused:all").distinct))
  .settings(scalacOptions += "-Wconf:msg=Flag.*repeatedly:s")

lazy val it = (project in file("it"))
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test")
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.it)
  .settings(scalacOptions := scalacOptions.value.diff(Seq("-Wunused:all")))
  .settings(scalacOptions += "-Wconf:msg=Flag.*repeatedly:s")
