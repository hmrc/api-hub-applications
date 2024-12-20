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
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Credential, Deleted}
import uk.gov.hmrc.apihubapplications.models.exception.{IdmsException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.mongojobs.EnvironmentParityDataMigrationJob.MigrationSummary
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EnvironmentParityDataMigrationJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  import EnvironmentParityDataMigrationJobSpec.*

  "EnvironmentParityDataMigrationJob" - {
    "must process applications and return the correct summary" in {
      val fixture = buildFixture()

      val applications = Seq(
        noCredentialsApplication,
        normalCredentialsApplication,
        hiddenCredentialApplication,
        clientNotFoundApplication,
        idmsFailsApplication,
        updateFailsApplication,
        failsApplication,
        deletedApplication
      )

      when(fixture.applicationsService.findAll(eqTo(None), eqTo(true)))
        .thenReturn(Future.successful(applications))

      when(fixture.idmsConnector.deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(hiddenCredentialCredential.clientId))(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.idmsConnector.deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientNotFoundCredential.clientId))(any))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientNotFoundCredential.clientId))))

      when(fixture.idmsConnector.deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(idmsFailsCredential.clientId))(any))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      when(fixture.applicationsRepository.update(any))
        .thenReturn(Future.successful(Right(())))

      when(fixture.applicationsRepository.update(eqTo(updateFailsApplication)))
        .thenReturn(Future.successful(Left(NotUpdatedException.forApplication(updateFailsApplication))))

      when(fixture.applicationsRepository.update(eqTo(failsApplication)))
        .thenReturn(Future.failed(new Throwable("Failed")))

      fixture.migrationJob.migrate().map(
        result =>
          verify(fixture.idmsConnector).deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(hiddenCredentialCredential.clientId))(any)
          verify(fixture.idmsConnector).deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientNotFoundCredential.clientId))(any)
          verify(fixture.idmsConnector).deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(idmsFailsCredential.clientId))(any)
          verify(fixture.idmsConnector, never()).deleteClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(deletedApplicationCredential.clientId))(any)
          verify(fixture.applicationsRepository).update(eqTo(hiddenCredentialApplicationUpdated))
          verify(fixture.applicationsRepository).update(eqTo(deletedApplicationUpdated))
          result mustBe MigrationSummary(8, 1, 1, 1)
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

  object Counters {

    private val applicationId = new AtomicInteger()
    private val clientId = new AtomicInteger()

    def nextApplicationId: Int = {
      applicationId.incrementAndGet()
    }

    def nextClientId: Int = {
      clientId.incrementAndGet()
    }

  }

  private def buildApplication(deleted: Boolean = false): Application = {
    val id = Counters.nextApplicationId

    val application = Application(
      id = Some(s"test-id-$id"),
      name = s"test-application-$id",
      createdBy = Creator("test-created-by"),
      teamMembers = Seq.empty
    )

    if (deleted) {
      application.delete(
        Deleted(
          deleted = LocalDateTime.now(),
          deletedBy = "test-deleted-by"
        )
      )
    }
    else {
      application
    }
  }

  private def buildCredential(hipEnvironment: HipEnvironment): Credential = {
    val id = Counters.nextClientId

    Credential(
      clientId = s"test-client-id-$id",
      created = LocalDateTime.now(),
      clientSecret = None,
      secretFragment = Some(s"test-secret-fragment-$id"),
      environmentId = hipEnvironment.id
    )
  }

  private def buildHiddenCredential(hipEnvironment: HipEnvironment): Credential = {
    val id = Counters.nextClientId

    Credential(
      clientId = s"test-client-id-$id",
      created = LocalDateTime.now(),
      clientSecret = None,
      secretFragment = None,
      environmentId = hipEnvironment.id
    )
  }

  private val noCredentialsApplication = buildApplication()

  private val normalCredentialsApplication = buildApplication()
    .setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(buildCredential(FakeHipEnvironments.primaryEnvironment)))
    .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(buildCredential(FakeHipEnvironments.secondaryEnvironment)))

  private val hiddenCredentialCredential = buildHiddenCredential(FakeHipEnvironments.primaryEnvironment)
  private val hiddenCredentialApplication = buildApplication()
    .setCredentials(
      FakeHipEnvironments.primaryEnvironment,
      Seq(
        buildCredential(FakeHipEnvironments.primaryEnvironment),
        hiddenCredentialCredential
      )
    )

  private val hiddenCredentialApplicationUpdated = hiddenCredentialApplication
    .removeCredential(FakeHipEnvironments.primaryEnvironment, hiddenCredentialCredential.clientId)

  private val clientNotFoundCredential = buildHiddenCredential(FakeHipEnvironments.primaryEnvironment)
  private val clientNotFoundApplication = buildApplication()
    .setCredentials(
      FakeHipEnvironments.primaryEnvironment,
      Seq(
        clientNotFoundCredential
      )
    )

  private val idmsFailsCredential = buildHiddenCredential(FakeHipEnvironments.primaryEnvironment)
  private val idmsFailsApplication = buildApplication()
    .setCredentials(
      FakeHipEnvironments.primaryEnvironment,
      Seq(
        idmsFailsCredential
      )
    )

  private val updateFailsApplication = buildApplication()

  private val failsApplication = buildApplication()

  private val deletedApplicationCredential = buildHiddenCredential(FakeHipEnvironments.primaryEnvironment)
  private val deletedApplication = buildApplication(deleted = true)
    .setCredentials(
      FakeHipEnvironments.primaryEnvironment,
      Seq(
        deletedApplicationCredential,
        buildCredential(FakeHipEnvironments.primaryEnvironment)
      )
    )
    .setCredentials(
      FakeHipEnvironments.secondaryEnvironment,
      Seq(
        buildCredential(FakeHipEnvironments.secondaryEnvironment)
      )
    )

  private val deletedApplicationUpdated = deletedApplication
    .removeCredential(FakeHipEnvironments.primaryEnvironment, deletedApplicationCredential.clientId)

}
