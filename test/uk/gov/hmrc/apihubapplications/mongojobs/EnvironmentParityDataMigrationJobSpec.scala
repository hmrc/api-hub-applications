/*
 * Copyright 2024 HM Revenue & Customs
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
import org.mockito.Mockito.{verify, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Credential}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, IdmsException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.mongojobs.EnvironmentParityDataMigrationJob.MigrationSummary
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EnvironmentParityDataMigrationJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  import EnvironmentParityDataMigrationJobSpec.*

  "EnvironmentParityDataMigrationJob" - {
    "must process applications and return the correct summary" in {
      val fixture = buildFixture()

      when(fixture.applicationsService.findAll(eqTo(None), eqTo(false)))
        .thenReturn(Future.successful(Seq(noCredentials, normalCredentials, hiddenCredential, idmsFails, updateFails, fails)))

      when(fixture.idmsConnector.deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(hiddenCredentialClientId))(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.idmsConnector.deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(idmsFailsClientId))(any))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      when(fixture.applicationsRepository.update(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.applicationsRepository.update(eqTo(updateFails)))
        .thenReturn(Future.successful(Left(NotUpdatedException.forApplication(updateFails))))

      when(fixture.applicationsRepository.update(eqTo(fails)))
        .thenReturn(Future.failed(new Throwable("Failed")))

      fixture.migrationJob.migrate().map(
        result =>
          verify(fixture.applicationsRepository).update(eqTo(hiddenCredentialUpdated))
          result mustBe MigrationSummary(6, 1, 1, 1)
      )
    }
  }

}

object EnvironmentParityDataMigrationJobSpec extends MockitoSugar {

  private case class Fixture(
    applicationsService: ApplicationsService,
    applicationsRepository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    migrationJob: EnvironmentParityDataMigrationJob
  )

  private def buildFixture(): Fixture = {
    val applicationsService = mock[ApplicationsService]
    val applicationsRepository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]

    val migrationJob = EnvironmentParityDataMigrationJob(applicationsService, applicationsRepository, FakeHipEnvironments, idmsConnector)

    Fixture(applicationsService, applicationsRepository, idmsConnector, migrationJob)
  }

  private def buildCredential(hipEnvironment: HipEnvironment, clientId: String): Credential = {
    Credential(
      clientId = clientId,
      created = LocalDateTime.now(),
      clientSecret = None,
      secretFragment = Some(s"test-secret-fragment-$clientId"),
      environmentId = hipEnvironment.id
    )
  }

  private def buildHiddenCredential(hipEnvironment: HipEnvironment, clientId: String): Credential = {
    Credential(
      clientId = clientId,
      created = LocalDateTime.now(),
      clientSecret = None,
      secretFragment = None,
      environmentId = hipEnvironment.id
    )
  }

  private val createdBy = Creator("test-email")
  private val team = Seq.empty

  private val noCredentials = Application(Some("test-id-1"), "test-application-1", createdBy, team)

  private val normalCredentials = Application(Some("test-id-2"), "test-application-2", createdBy, team)
    .setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(buildCredential(FakeHipEnvironments.primaryEnvironment, "test-client-id-1")))
    .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(buildCredential(FakeHipEnvironments.secondaryEnvironment, "test-client-id-2")))

  private val hiddenCredentialClientId = "test-client-id-4"
  private val hiddenCredential = Application(Some("test-id-3"), "test-application-3", createdBy, team)
    .setCredentials(
      FakeHipEnvironments.primaryEnvironment,
      Seq(
        buildCredential(FakeHipEnvironments.primaryEnvironment, "test-client-id-3"),
        buildHiddenCredential(FakeHipEnvironments.primaryEnvironment, hiddenCredentialClientId)
      )
    )

  private val hiddenCredentialUpdated = hiddenCredential
    .removeCredential(FakeHipEnvironments.primaryEnvironment, hiddenCredentialClientId)

  private val idmsFailsClientId = "test-client-id-5"
  private val idmsFails = Application(Some("test-id-4"), "test-application-4", createdBy, team)
    .setCredentials(
      FakeHipEnvironments.primaryEnvironment,
      Seq(
        buildHiddenCredential(FakeHipEnvironments.primaryEnvironment, idmsFailsClientId)
      )
    )

  private val updateFails = Application(Some("test-id-5"), "test-application-5", createdBy, team)

  private val fails = Application(Some("test-id-6"), "test-application-6", createdBy, team)

}
