/*
 * Copyright 2025 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apihubapplications.mongojobs

import com.google.inject.{Inject, Singleton}
import com.typesafe.config.Config
import play.api.{ConfigLoader, Configuration, Logging}
import uk.gov.hmrc.apihubapplications.config.{DefaultHipEnvironment, HipEnvironment}
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Application, Credential}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class DeletePreProductionCredentialsJob @Inject()(
  clock: Clock,
  configuration: Configuration,
  applicationsService: ApplicationsService,
  idmsConnector: IdmsConnector,
  applicationsRepository: ApplicationsRepository
)(implicit ec: ExecutionContext) extends MongoJob with Logging {

  import DeletePreProductionCredentialsJob.*

  override def run(): Future[Unit] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val hipEnvironment = configuration.get[DefaultHipEnvironment]("mongoJob.preProduction")

    deleteCredentials(hipEnvironment).map(_ => ())
  }

  def deleteCredentials(hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[MigrationSummary] = {

    applicationsService
      .findAll(None, includeDeleted = true)
      .map(
        applications =>
          applications.map(application => processApplication(hipEnvironment, application))
      )
      .flatMap(Future.sequence(_))
      .map(
        _.foldRight(MigrationSummary())((result, summary) => summary.add(result))
      )
      .andThen {
        case Success(summary) =>
          logger.info(s"Delete pre-production credentials migration complete: summary=${summary.report}")
        case Failure(e) =>
          logger.error("Delete pre-production credentials migration failed", e)
      }

  }

  private def processApplication(
    hipEnvironment: HipEnvironment, 
    application: Application
  )(implicit hc: HeaderCarrier): Future[MigrationResult] = {

    Future.sequence(
      application
        .credentials
        .filter(_.environmentId == hipEnvironment.id)
        .toSeq.map(
          credential =>
            deleteCredential(hipEnvironment, application, credential)
        )
    )
      .map(compileResult)
      .flatMap(result => updateApplication(hipEnvironment, application, result))

  }

  private def deleteCredential(
    hipEnvironment: HipEnvironment,
    application: Application,
    credential: Credential
  )(implicit hc: HeaderCarrier): Future[Either[IdmsException, Credential]] = {

    idmsConnector
      .deleteClient(hipEnvironment, credential.clientId)
      .map {
        case Right(_) =>
          logger.info(s"Credential deleted: applicationId=${application.id}; clientId=${credential.clientId}")
          Right(credential)
        case Left(e) if e.issue == ClientNotFound =>
          logger.info(s"Credential not found, considered deleted: applicationId=${application.id}; clientId=${credential.clientId}")
          Right(credential)
        case Left(e) =>
          logger.warn(s"Error deleting credential: applicationId=${application.id}; clientId=${credential.clientId}")
          Left(e)
      }

  }

  private def compileResult(results: Seq[Either[IdmsException, Credential]]): MigrationResult = {

    results.foldLeft(MigrationResult())(
      (migrationResult, result) => result match {
        case Right(credential) => migrationResult.success(credential)
        case Left(e) => migrationResult.failure()
      }
    )

  }

  private def updateApplication(
    hipEnvironment: HipEnvironment,
    application: Application,
    result: MigrationResult
  ): Future[MigrationResult] = {

    if (result.deleted.nonEmpty) {
      applicationsRepository.update(
        result.deleted.foldLeft(application)(
          (application, credential) =>
            application.removeCredential(hipEnvironment, credential.clientId)
        ).updated(clock)
      ).map {
        case Right(_) => result
        case Left(e) =>
          logger.warn(s"Error updating application ${application.id}", e)
          result
      }
    }
    else {
      Future.successful(result)
    }

  }

}

object DeletePreProductionCredentialsJob {

  case class MigrationSummary(
    applications: Int,
    withoutCredentials: Int,
    withCredentials: Int,
    credentials: Int,
    deleted: Int,
    failed: Int
  ) {

    def add(result: MigrationResult): MigrationSummary = {
      result match {
        case result @ MigrationResult(_, _) =>
          this.copy(
            applications = applications + 1,
            withoutCredentials = withoutCredentials + (if (result.count == 0) 1 else 0),
            withCredentials = withCredentials + (if (result.count > 0) 1 else 0),
            credentials = credentials + result.count,
            deleted = deleted + result.deleted.size,
            failed = failed + result.failed
          )
      }
    }

    def report: String = {
      Seq(
        s"applications: $applications",
        s"withoutCredentials: $withoutCredentials",
        s"withCredentials: $withCredentials",
        s"credentials: $credentials",
        s"deleted: $deleted",
        s"failed: $failed"
      ).mkString(", ")
    }

  }

  object MigrationSummary {

    def apply(): MigrationSummary = {
      MigrationSummary(
        applications = 0,
        withoutCredentials = 0,
        withCredentials = 0,
        credentials = 0,
        deleted = 0,
        failed = 0
      )
    }

  }

  case class MigrationResult(count: Int, deleted: Seq[Credential]) {

    def success(credential: Credential): MigrationResult = {
      this.copy(count = count + 1, deleted :+ credential)
    }

    def failure(): MigrationResult = {
      this.copy(count = count + 1)
    }

    def failed: Int = count - deleted.size

  }

  object MigrationResult {

    def apply(): MigrationResult = {
      MigrationResult(
        count = 0,
        deleted = Seq.empty
      )
    }

  }

  implicit val hipEnvironmentConfigLoader: ConfigLoader[DefaultHipEnvironment] = new ConfigLoader[DefaultHipEnvironment] {

    override def load(rootConfig: Config, path: String): DefaultHipEnvironment = {
      val config = rootConfig.getConfig(path)

      DefaultHipEnvironment(
        id = "preprod",
        name = "Pre-production",
        rank = 0,
        isProductionLike = false,
        apimUrl = config.getString("apimUrl"),
        clientId = config.getString("clientId"),
        secret = config.getString("secret"),
        useProxy = false,
        apiKey = None,
        promoteTo = None,
        apimEnvironmentName = config.getString("apimEnvironmentName")
      )
    }

  }

}
