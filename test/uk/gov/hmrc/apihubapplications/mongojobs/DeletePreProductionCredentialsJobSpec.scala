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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.scalatest.OptionValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.apihubapplications.config.{DefaultHipEnvironment, HipEnvironment}
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Credential}
import uk.gov.hmrc.apihubapplications.models.exception.{IdmsException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.mongojobs.DeletePreProductionCredentialsJob.MigrationSummary
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeletePreProductionCredentialsJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar with OptionValues {

  import DeletePreProductionCredentialsJobSpec.*

  "DeletePreProductionCredentialsJob" - {

    "must process an empty list of applications and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.applicationsService.findAll(any, any)).thenReturn(Future.successful(Seq.empty))

      fixture.job.deleteCredentials(preProdHipEnvironment).map {
        result =>
          verify(fixture.applicationsService).findAll(eqTo(None), eqTo(true))
          result mustBe MigrationSummary()
      }
    }

    "must process a list of applications and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.applicationsService.findAll(any, any))
        .thenReturn(Future.successful(Seq(
          noPreProdCredsApplication,
          onePreProdCredApplication,
          twoPreProdCredsApplication,
          clientNotFoundApplication,
          idmsExceptionApplication,
          updateExceptionApplication
        )))

      when(fixture.idmsConnector.deleteClient(any, any)(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.idmsConnector.deleteClient(any, eqTo(clientIdFrom(clientNotFoundApplication)))(any))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientIdFrom(clientNotFoundApplication)))))

      when(fixture.idmsConnector.deleteClient(any, eqTo(clientIdFrom(idmsExceptionApplication)))(any))
        .thenReturn(Future.successful(Left(IdmsException.error(new Throwable()))))

      when(fixture.applicationsRepository.update(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.applicationsRepository.update(eqTo(updatedApplication(updateExceptionApplication))))
        .thenReturn(Future.successful(Left(NotUpdatedException.forApplication(updateExceptionApplication))))

      fixture.job.deleteCredentials(preProdHipEnvironment).map {
        result =>
          verifyIdmsDeleteClient(fixture, onePreProdCredApplication)
          verifyIdmsDeleteClient(fixture, twoPreProdCredsApplication)
          verifyIdmsDeleteClient(fixture, clientNotFoundApplication)
          verifyIdmsDeleteClient(fixture, idmsExceptionApplication)
          verifyIdmsDeleteClient(fixture, updateExceptionApplication)
          verifyNoMoreInteractions(fixture.idmsConnector)

          verifyRepositoryUpdate(fixture, onePreProdCredApplication)
          verifyRepositoryUpdate(fixture, twoPreProdCredsApplication)
          verifyRepositoryUpdate(fixture, clientNotFoundApplication)
          verifyRepositoryUpdate(fixture, updateExceptionApplication)
          verifyNoMoreInteractions(fixture.applicationsRepository)

          result mustBe MigrationSummary(
            applications = 6,
            withoutCredentials = 1,
            withCredentials = 5,
            credentials = 6,
            deleted = 5,
            failed = 1
          )
      }
    }

  }

  private def verifyIdmsDeleteClient(fixture: Fixture, application: Application): Unit = {
    application.getCredentials(preProdHipEnvironment).foreach(
      credential =>
        verify(fixture.idmsConnector).deleteClient(eqTo(preProdHipEnvironment), eqTo(credential.clientId))(any)
    )
  }

  private def updatedApplication(application: Application): Application = {
    application
      .setCredentials(preProdHipEnvironment, Seq.empty)
      .updated(clock)
  }

  private def verifyRepositoryUpdate(fixture: Fixture, application: Application): Unit = {
    verify(fixture.applicationsRepository).update(eqTo(updatedApplication(application)))
  }

  private def clientIdFrom(application: Application): String = {
    application
      .getCredentials(preProdHipEnvironment)
      .headOption
      .value
      .clientId
  }

}

private object DeletePreProductionCredentialsJobSpec extends MockitoSugar {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  val preProdHipEnvironment: HipEnvironment = DefaultHipEnvironment(
    id = "preprod",
    rank = 1,
    isProductionLike = false,
    apimUrl = "test-apim-url",
    clientId = "test-client-id",
    secret = "test-secret",
    useProxy = false,
    apiKey = None,
    promoteTo = None,
    apimEnvironmentName = "test-apim-name"
  )

  val configuration: Configuration = Configuration.from(
    Map(
      "mongoJob.preProduction.apimUrl" -> preProdHipEnvironment.apimUrl,
      "mongoJob.preProduction.clientId" -> preProdHipEnvironment.clientId,
      "mongoJob.preProduction.secret" -> preProdHipEnvironment.secret,
      "mongoJob.preProduction.apimEnvironmentName" -> preProdHipEnvironment.apimEnvironmentName
    )
  )

  private val createdBy = Creator("test-email")
  private val team = Seq.empty

  private val noPreProdCredsApplication = buildApplication(1, 0)
  private val onePreProdCredApplication = buildApplication(2, 1)
  private val twoPreProdCredsApplication = buildApplication(3, 2)
  private val clientNotFoundApplication = buildApplication(4, 1)
  private val idmsExceptionApplication = buildApplication(5, 1)
  private val updateExceptionApplication = buildApplication(6, 1)

  def buildApplication(index: Int, preProdCreds: Int): Application = {
    Application(Some(s"test-id-$index"), s"test-application-$index", createdBy, team).copy(
      credentials = Set(
        buildCredential(index, 1, FakeHipEnvironments.productionEnvironment),
        buildCredential(index, 2, FakeHipEnvironments.testEnvironment)
      ) ++ (1 to preProdCreds).map(
        preProdCred =>
          buildCredential(index, preProdCred + 2, preProdHipEnvironment)
      ).toSet
    )
  }

  def buildCredential(index: Int, credentialIndex: Int, hipEnvironment: HipEnvironment): Credential = {
    Credential(
      clientId = s"test-credential-id-$index-$credentialIndex",
      created = LocalDateTime.now(clock),
      clientSecret = None,
      secretFragment = None,
      environmentId = hipEnvironment.id
    )
  }

  case class Fixture(
    applicationsService: ApplicationsService,
    idmsConnector: IdmsConnector,
    applicationsRepository: ApplicationsRepository,
    job: DeletePreProductionCredentialsJob
  )

  def buildFixture(): Fixture = {
    val applicationsService = mock[ApplicationsService]
    val idmsConnector = mock[IdmsConnector]
    val applicationsRepository = mock[ApplicationsRepository]
    val job = new DeletePreProductionCredentialsJob(clock, configuration, applicationsService, idmsConnector, applicationsRepository)
    Fixture(applicationsService, idmsConnector, applicationsRepository, job)
  }

}
