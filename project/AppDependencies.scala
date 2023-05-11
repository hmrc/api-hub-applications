import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val bootstrapVersion = "7.15.0"
  private val hmrcMongoVersion = "1.2.0"
  private val internalAuthVersion = "1.4.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-28"    % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-28"           % hmrcMongoVersion,
    "uk.gov.hmrc"             %% "internal-auth-client-play-28" % internalAuthVersion
  )

  val test = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-28"     % bootstrapVersion            % "test, it",
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-test-play-28"    % hmrcMongoVersion            % "test, it",
    "org.mockito"             %% "mockito-scala"              % "1.17.14"                   % "test, it",
    "org.scalacheck"          %% "scalacheck"                 % "1.17.0"                    % "test, it",
    "org.scalatestplus"       %% "scalacheck-1-17"            % "3.2.15.0"                  % "test, it"
  )
}
