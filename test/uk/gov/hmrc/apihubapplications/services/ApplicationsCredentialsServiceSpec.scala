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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{clearInvocations, verify, verifyNoInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationCredentialLimitException, ApplicationNotFoundException, CredentialNotFoundException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import java.time.*
import scala.concurrent.Future

class ApplicationsCredentialsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with TableDrivenPropertyChecks with EitherValues {

  import ApplicationsCredentialsServiceSpec.*

  "getCredentials" - {
    "must return production credentials unchanged without calling IDMS" in {
      val fixture = buildFixture
      import fixture.*

      val hipEnvironment = FakeHipEnvironments.productionEnvironment

      val productionCredentials = Seq(
        buildCredential("test-client-id-1", FakeHipEnvironments.productionEnvironment),
        buildCredential("test-client-id-2", FakeHipEnvironments.productionEnvironment)
      )

      val testCredentials = Seq(
        buildCredential("test-client-id-3", FakeHipEnvironments.testEnvironment),
        buildCredential("test-client-id-4", FakeHipEnvironments.testEnvironment)
      )

      val application = baseApplication
        .setCredentials(FakeHipEnvironments.productionEnvironment, productionCredentials)
        .setCredentials(FakeHipEnvironments.testEnvironment, testCredentials)

      when(searchService.findById(eqTo(application.safeId))(any)).thenReturn(Future.successful(Right(application)))

      service.getCredentials(application.safeId, hipEnvironment)(HeaderCarrier()).map(
        result =>
          verifyNoInteractions(idmsConnector)
          result.value mustBe productionCredentials
      )
    }

    "must return non-production credentials with secrets" in {
      val fixture = buildFixture
      import fixture.*

      val hipEnvironment = FakeHipEnvironments.testEnvironment

      val productionCredentials = Seq(
        buildCredential("test-client-id-1", FakeHipEnvironments.productionEnvironment),
        buildCredential("test-client-id-2", FakeHipEnvironments.productionEnvironment)
      )

      val testCredentials = Seq(
        buildCredential("test-client-id-3", FakeHipEnvironments.testEnvironment),
        buildCredential("test-client-id-4", FakeHipEnvironments.testEnvironment)
      )

      val application = baseApplication
        .setCredentials(FakeHipEnvironments.productionEnvironment, productionCredentials)
        .setCredentials(FakeHipEnvironments.testEnvironment, testCredentials)

      when(searchService.findById(eqTo(application.safeId))(any)).thenReturn(Future.successful(Right(application)))

      testCredentials.foreach(
        credential =>
          when(idmsConnector.fetchClient(eqTo(hipEnvironment), eqTo(credential.clientId))(any))
            .thenReturn(Future.successful(Right(buildClientResponse(credential))))
      )

      val expected = testCredentials.map(
        credential => credential.setSecret(secretForCredential(credential))
      )

      service.getCredentials(application.safeId, hipEnvironment)(HeaderCarrier()).map(
        result =>
          result.value mustBe expected
      )
    }

    "must return ApplicationNotFoundException if the application cannot be found" in {
      val fixture = buildFixture
      import fixture.*

      val expected = ApplicationNotFoundException.forApplication(baseApplication)

      when(searchService.findById(eqTo(baseApplication.safeId))(any))
        .thenReturn(Future.successful(Left(expected)))

      service.getCredentials(baseApplication.safeId, FakeHipEnvironments.productionEnvironment)(HeaderCarrier()).map(
        result =>
          result.left.value mustBe expected
      )
    }

    "must return the first IdmsException if one is encountered" in {
      val fixture = buildFixture
      import fixture.*

      val hipEnvironment = FakeHipEnvironments.testEnvironment

      val credential1 = buildCredential("test-client-id-1", hipEnvironment)
      val credential2 = buildCredential("test-client-id-2", hipEnvironment)

      val testCredentials = Seq(credential1, credential2)

      val application = baseApplication
        .setCredentials(FakeHipEnvironments.testEnvironment, testCredentials)

      val expected = IdmsException.unexpectedResponse(500)

      when(searchService.findById(eqTo(application.safeId))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(eqTo(hipEnvironment), eqTo(credential1.clientId))(any))
        .thenReturn(Future.successful(Right(buildClientResponse(credential1))))

      when(idmsConnector.fetchClient(eqTo(hipEnvironment), eqTo(credential2.clientId))(any))
        .thenReturn(Future.successful(Left(expected)))

      service.getCredentials(application.safeId, hipEnvironment)(HeaderCarrier()).map(
        result =>
          result.left.value mustBe expected
      )
    }
  }

  "addCredential" - {

    "must create a new secondary credential and call ScopeFixer" in {
      val fixture = buildFixture
      import fixture.*

      val testAppId = "test-app-id"
      val oldTestClientId = "test-client-id"
      val newTestClientId = "new-test-client-id"
      val oldSecret = "test-secret-9876"
      val newSecret = "test-secret-1234"
      val scopeName = "test-scope"
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), Some(oldSecret), Some("9876"), FakeHipEnvironments.testEnvironment.id)
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some("test-secret-1234"), Some("1234"), FakeHipEnvironments.testEnvironment.id)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set(existingCredential)
      )

      val newClientResponse = ClientResponse(newTestClientId, newSecret)

      val expectedClient = Client(app.name, app.name)
      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.testEnvironment), any)(any)).thenReturn(Future.successful(Right(newClientResponse)))
      when(searchService.findById(eqTo(testAppId))(any)).thenReturn(Future.successful(Right(app)))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))

      val updatedApp = app
        .addCredential(FakeHipEnvironments.testEnvironment, expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(eventService.createCredential(any, any, any, any)).thenReturn(Future.successful(()))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty), eqTo(expectedCredential))(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, secondaryEnvironment, testUserEmail)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(FakeHipEnvironments.testEnvironment), eqTo(expectedClient))(any)
          verify(repository).update(updatedApp)
          verify(eventService).createCredential(eqTo(updatedApp), eqTo(expectedCredential), eqTo(testUserEmail), eqTo(LocalDateTime.now(clock)))
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must create a new primary credential and call ScopeFixer where the existing credential is not hidden" in {
      val fixture = buildFixture
      import fixture.*

      val testAppId = "test-app-id"
      val oldTestClientId = "test-client-id"
      val newTestClientId = "new-test-client-id"
      val newSecret = "test-secret-1234"
      val scopeName = "test-scope"
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, Some("9876"), FakeHipEnvironments.productionEnvironment.id)
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"), FakeHipEnvironments.productionEnvironment.id)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set(existingCredential)
      )

      val newClientResponse = ClientResponse(newTestClientId, newSecret)
      val expectedClient = Client(app.name, app.name)

      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.productionEnvironment), any)(any)).thenReturn(Future.successful(Right(newClientResponse)))
      when(searchService.findById(eqTo(testAppId))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addCredential(FakeHipEnvironments.productionEnvironment, expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(eventService.createCredential(any, any, any, any)).thenReturn(Future.successful(()))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty), eqTo(expectedCredential))(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, primaryEnvironment, testUserEmail)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(expectedClient))(any)
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must return ApplicationCredentialLimitException if there are already 5 credentials" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      ).setCredentials(
        FakeHipEnvironments.productionEnvironment, 
        (1 to 5).map(i => Credential(s"test-client-$i", LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))
      )

      when(idmsConnector.fetchClientScopes(any, any)(any)).thenReturn(Future.successful(Right(Seq.empty)))
      when(searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))

      service.addCredential(applicationId, primaryEnvironment, testUserEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, primaryEnvironment))
      }
    }
  }

  "deleteCredential" - {
    "must delete the correct credential from the correct environment" in {
      val hipEnvironments = Table(
        "Hip Environment",
        primaryEnvironment,
        secondaryEnvironment
      )

      val applicationId = "test-id"
      val clientId = "test-client-id"

      forAll(hipEnvironments) {(hipEnvironment: HipEnvironment) =>
        val fixture = buildFixture
        import fixture.*

        val application = Application(
          id = Some(applicationId),
          name = "test-name",
          created = LocalDateTime.now(clock),
          createdBy = Creator("test-email"),
          lastUpdated = LocalDateTime.now(clock).minusDays(1),
          teamMembers = Seq(TeamMember(email = "test-email")),
          credentials = Set.empty
        )
          .addCredential(hipEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, hipEnvironment.id))
          .addCredential(hipEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, hipEnvironment.id))

        val updated = application
          .removeCredential(hipEnvironment, clientId)
          .updated(LocalDateTime.now(clock))

        when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))
        when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
        when(repository.update(any)).thenReturn(Future.successful(Right(())))
        when(eventService.revokeCredential(any, any, any, any, any)).thenReturn(Future.successful(()))

        service.deleteCredential(applicationId, hipEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
          result =>
            verify(idmsConnector).deleteClient(eqTo(hipEnvironment), eqTo(clientId))(any)
            verify(eventService).revokeCredential(
              eqTo(updated),
              eqTo(hipEnvironment),
              eqTo(clientId),
              eqTo(testUserEmail),
              eqTo(LocalDateTime.now(clock))
            )
            result mustBe Right(())
        }
      }
    }

    "must update the application in the repository" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential("test-primary-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))
        .addCredential(FakeHipEnvironments.testEnvironment, Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.testEnvironment.id))

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(eventService.revokeCredential(any, any, any, any, any)).thenReturn(Future.successful(()))

      service.deleteCredential(applicationId, primaryEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
        result =>
          val updated = application
            .copy(lastUpdated = LocalDateTime.now(clock))
            .removeCredential(FakeHipEnvironments.productionEnvironment, clientId)
          verify(repository).update(eqTo(updated))
          result mustBe Right(())
      }
    }

    "must succeed when IDMS returns ClientNotFound" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientId))))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(eventService.revokeCredential(any, any, any, any, any)).thenReturn(Future.successful(()))

      service.deleteCredential(applicationId, primaryEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.deleteCredential(applicationId, primaryEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return CredentialNotFoundException when the credential does not exist in the application" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, primaryEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(CredentialNotFoundException.forClientId(clientId))
      }
    }

    "must return IdmsException when this is returned from IDMS" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      service.deleteCredential(applicationId, primaryEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }

    "must return ApplicationCredentialLimitException when an attempt is made to delete the last credential" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )
        .addCredential(FakeHipEnvironments.productionEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.productionEnvironment.id))

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, primaryEnvironment, clientId, testUserEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, primaryEnvironment))
      }
    }
  }

  "fetchAllScopes" - {
    "must return the correct credential scopes" in {
      val fixture = buildFixture
      import fixture.*

      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val clientId3 = "test-client-id-3"
      val clientId4 = "test-client-id-4"

      val application = baseApplication
        .addCredential(FakeHipEnvironments.productionEnvironment, buildCredential(clientId1, FakeHipEnvironments.productionEnvironment))
        .addCredential(FakeHipEnvironments.productionEnvironment, buildCredential(clientId2, FakeHipEnvironments.productionEnvironment))
        .addCredential(FakeHipEnvironments.testEnvironment, buildCredential(clientId3, FakeHipEnvironments.testEnvironment))
        .addCredential(FakeHipEnvironments.testEnvironment, buildCredential(clientId4, FakeHipEnvironments.testEnvironment))

      when(searchService.findById(eqTo(application.safeId))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(clientId1))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId1))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(clientId2))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId2))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.testEnvironment), eqTo(clientId3))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId3))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.testEnvironment), eqTo(clientId4))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId4))))
      
      when(hipEnvironments.environments).thenReturn(Seq(primaryEnvironment, secondaryEnvironment))
      
      val expected = Seq(
        buildCredentialScopes(FakeHipEnvironments.productionEnvironment.id, clientId1),
        buildCredentialScopes(FakeHipEnvironments.productionEnvironment.id, clientId2),
        buildCredentialScopes(FakeHipEnvironments.testEnvironment.id, clientId3),
        buildCredentialScopes(FakeHipEnvironments.testEnvironment.id, clientId4)
      )

      service.fetchAllScopes(application.safeId)(HeaderCarrier()).map {
        result =>
          result.value must contain theSameElementsAs expected
      }
    }

    "must return application not found exception when it does not exist" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-application-id"
      val expected = ApplicationNotFoundException.forId(applicationId)

      when(searchService.findById(eqTo(applicationId))(any))
        .thenReturn(Future.successful(Left(expected)))

      service.fetchAllScopes(applicationId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }

    "must return an IdmsException if any IDMS call fails but others succeed" in {
      val fixture = buildFixture
      import fixture.*

      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"

      val application = baseApplication
        .addCredential(FakeHipEnvironments.productionEnvironment, buildCredential(clientId1, FakeHipEnvironments.productionEnvironment))
        .addCredential(FakeHipEnvironments.productionEnvironment, buildCredential(clientId2, FakeHipEnvironments.productionEnvironment))

      val expected = IdmsException.unexpectedResponse(500)

      when(searchService.findById(eqTo(application.safeId))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(clientId1))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId1))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.productionEnvironment), eqTo(clientId2))(any)).thenReturn(Future.successful(Left(expected)))

      when(hipEnvironments.environments).thenReturn(Seq(primaryEnvironment, secondaryEnvironment))
      
      service.fetchAllScopes(application.safeId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }
  }

  private def buildFixture: Fixture = {
    val searchService =  mock[ApplicationsSearchService]
    val repository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]
    val accessRequestsService = mock[AccessRequestsService]
    val scopeFixer = mock[ScopeFixer]
    val hipEnvironments = mock[HipEnvironments]
    val eventService = mock[ApplicationsEventService]
    val service = new ApplicationsCredentialsServiceImpl(searchService, repository, idmsConnector, clock, accessRequestsService, scopeFixer, hipEnvironments, eventService)
    Fixture(searchService, repository, idmsConnector, accessRequestsService, scopeFixer, service, hipEnvironments, eventService)
  }

  private case class Fixture (
    searchService: ApplicationsSearchService,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    accessRequestsService: AccessRequestsService,
    scopeFixer: ScopeFixer,
    service: ApplicationsCredentialsService,
    hipEnvironments: HipEnvironments,
    eventService: ApplicationsEventService
  )

}

object ApplicationsCredentialsServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  val testUserEmail: String = "test-user-email"

  val baseApplication: Application = Application(
    id = Some("test-application-id"),
    name = "test-application-name",
    createdBy = Creator("test-creator-email"),
    teamId = "test-team-id"
  )

  def buildCredential(clientId: String, hipEnvironment: HipEnvironment): Credential = {
    Credential(clientId = clientId, created = LocalDateTime.now(clock), clientSecret = None, secretFragment = None, hipEnvironment.id)
  }

  def buildClientScopes(clientId: String): Seq[ClientScope] = {
    Seq(
      ClientScope(s"$clientId-scope-1"),
      ClientScope(s"$clientId-scope-2")
    )
  }

  def buildCredentialScopes(environmentId: String, clientId: String): CredentialScopes = {
    CredentialScopes(
      environmentId = environmentId,
      clientId = clientId,
      created = LocalDateTime.now(clock),
      scopes = buildClientScopes(clientId).map(_.clientScopeId)
    )
  }

  def secretForCredential(credential: Credential): String = {
    s"secret-for-${credential.clientId}"
  }

  def buildClientResponse(credential: Credential): ClientResponse = {
    ClientResponse(clientId = credential.clientId, secret = secretForCredential(credential))
  }

  val primaryEnvironment: HipEnvironment = FakeHipEnvironments.productionEnvironment
  val secondaryEnvironment: HipEnvironment = FakeHipEnvironments.testEnvironment
  
}
