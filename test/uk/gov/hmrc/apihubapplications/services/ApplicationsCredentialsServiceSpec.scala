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
import org.mockito.Mockito.{times, verify, verifyNoInteractions, when}
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
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}
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

      val hipEnvironment = FakeHipEnvironments.primaryEnvironment

      val productionCredentials = Seq(
        buildCredential("test-client-id-1", FakeHipEnvironments.primaryEnvironment),
        buildCredential("test-client-id-2", FakeHipEnvironments.primaryEnvironment)
      )

      val testCredentials = Seq(
        buildCredential("test-client-id-3", FakeHipEnvironments.secondaryEnvironment),
        buildCredential("test-client-id-4", FakeHipEnvironments.secondaryEnvironment)
      )

      val application = baseApplication
        .setCredentials(FakeHipEnvironments.primaryEnvironment, productionCredentials)
        .setCredentials(FakeHipEnvironments.secondaryEnvironment, testCredentials)

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      service.getCredentials(application.safeId, hipEnvironment)(HeaderCarrier()).map(
        result =>
          verifyNoInteractions(idmsConnector)
          result.value mustBe productionCredentials
      )
    }

    "must return non-production credentials with secrets" in {
      val fixture = buildFixture
      import fixture.*

      val hipEnvironment = FakeHipEnvironments.secondaryEnvironment

      val productionCredentials = Seq(
        buildCredential("test-client-id-1", FakeHipEnvironments.primaryEnvironment),
        buildCredential("test-client-id-2", FakeHipEnvironments.primaryEnvironment)
      )

      val testCredentials = Seq(
        buildCredential("test-client-id-3", FakeHipEnvironments.secondaryEnvironment),
        buildCredential("test-client-id-4", FakeHipEnvironments.secondaryEnvironment)
      )

      val application = baseApplication
        .setCredentials(FakeHipEnvironments.primaryEnvironment, productionCredentials)
        .setCredentials(FakeHipEnvironments.secondaryEnvironment, testCredentials)

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

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

      when(searchService.findById(eqTo(baseApplication.safeId), eqTo(false))(any))
        .thenReturn(Future.successful(Left(expected)))

      service.getCredentials(baseApplication.safeId, FakeHipEnvironments.primaryEnvironment)(HeaderCarrier()).map(
        result =>
          result.left.value mustBe expected
      )
    }

    "must return the first IdmsException if one is encountered" in {
      val fixture = buildFixture
      import fixture.*

      val hipEnvironment = FakeHipEnvironments.secondaryEnvironment

      val credential1 = buildCredential("test-client-id-1", hipEnvironment)
      val credential2 = buildCredential("test-client-id-2", hipEnvironment)

      val testCredentials = Seq(credential1, credential2)

      val application = baseApplication
        .setCredentials(FakeHipEnvironments.secondaryEnvironment, testCredentials)

      val expected = IdmsException.unexpectedResponse(500)

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

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
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), Some(oldSecret), Some("9876"), FakeHipEnvironments.secondaryEnvironment.id)
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some("test-secret-1234"), Some("1234"), FakeHipEnvironments.secondaryEnvironment.id)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(), secondary = Environment(Seq(Scope(scopeName)), Seq(existingCredential)))
      )

      val newClientResponse = ClientResponse(newTestClientId, newSecret)

      val expectedClient = Client(app.name, app.name)
      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.secondaryEnvironment), any)(any)).thenReturn(Future.successful(Right(newClientResponse)))
      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))

      val updatedApp = app
        .addCredential(FakeHipEnvironments.secondaryEnvironment, expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty))(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, secondaryEnvironment)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(expectedClient))(any)
          verify(repository).update(updatedApp)
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
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, Some("9876"), FakeHipEnvironments.primaryEnvironment.id)
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"), FakeHipEnvironments.primaryEnvironment.id)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(Seq(Scope(scopeName)), Seq(existingCredential)), secondary = Environment())
      )

      val newClientResponse = ClientResponse(newTestClientId, newSecret)
      val expectedClient = Client(app.name, app.name)

      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.primaryEnvironment), any)(any)).thenReturn(Future.successful(Right(newClientResponse)))
      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addCredential(FakeHipEnvironments.primaryEnvironment, expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty))(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, primaryEnvironment)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(expectedClient))(any)
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must update existing primary credential and set a secret fragment where the existing credential is hidden" in {
      val fixture = buildFixture
      import fixture.*

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val scopeName = "test-scope"

      val existingHiddenCredential = Credential(testClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, None, FakeHipEnvironments.primaryEnvironment.id)
      val newSecret = "test-secret-1234"

      val expectedCredential = Credential(testClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"), FakeHipEnvironments.primaryEnvironment.id)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(Seq(Scope(scopeName)), Seq(existingHiddenCredential)), secondary = Environment())
      )

      when(idmsConnector.newSecret(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientId))(any)).thenReturn(Future.successful(Right(Secret(newSecret))))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app.setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(expectedCredential))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty))(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, primaryEnvironment)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector, times(0)).createClient(eqTo(FakeHipEnvironments.primaryEnvironment), any)(any)
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
        environments = Environments()
      ).setCredentials(
        FakeHipEnvironments.primaryEnvironment, 
        (1 to 5).map(i => Credential(s"test-client-$i", LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
      )

      when(idmsConnector.fetchClientScopes(any, any)(any)).thenReturn(Future.successful(Right(Seq.empty)))
      when(searchService.findById(any, any)(any)).thenReturn(Future.successful(Right(application)))

      service.addCredential(applicationId, primaryEnvironment)(HeaderCarrier()).map {
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
          lastUpdated = LocalDateTime.now(clock),
          teamMembers = Seq(TeamMember(email = "test-email")),
          environments = Environments()
        )
          .addCredential(hipEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, hipEnvironment.id))
          .addCredential(hipEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, hipEnvironment.id))

        when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
        when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
        when(repository.update(any)).thenReturn(Future.successful(Right(())))

        service.deleteCredential(applicationId, hipEnvironment, clientId)(HeaderCarrier()).map {
          result =>
            verify(idmsConnector).deleteClient(eqTo(hipEnvironment), eqTo(clientId))(any)
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
        environments = Environments()
      )
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential("test-primary-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.secondaryEnvironment, Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.secondaryEnvironment.id))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, primaryEnvironment, clientId)(HeaderCarrier()).map {
        result =>
          val updated = application
            .copy(lastUpdated = LocalDateTime.now(clock))
            .removeCredential(FakeHipEnvironments.primaryEnvironment, clientId)
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
        environments = Environments()
      )
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientId))))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, primaryEnvironment, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture.*

      val applicationId = "test-id"
      val clientId = "test-client-id"

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.deleteCredential(applicationId, primaryEnvironment, clientId)(HeaderCarrier()).map {
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
        environments = Environments()
      )
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, primaryEnvironment, clientId)(HeaderCarrier()).map {
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
        environments = Environments()
      )
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential("other-credential-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      service.deleteCredential(applicationId, primaryEnvironment, clientId)(HeaderCarrier()).map {
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
        environments = Environments()
      )
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, primaryEnvironment, clientId)(HeaderCarrier()).map {
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
        .addCredential(FakeHipEnvironments.primaryEnvironment, buildCredential(clientId1, FakeHipEnvironments.primaryEnvironment))
        .addCredential(FakeHipEnvironments.primaryEnvironment, buildCredential(clientId2, FakeHipEnvironments.primaryEnvironment))
        .addCredential(FakeHipEnvironments.secondaryEnvironment, buildCredential(clientId3, FakeHipEnvironments.secondaryEnvironment))
        .addCredential(FakeHipEnvironments.secondaryEnvironment, buildCredential(clientId4, FakeHipEnvironments.secondaryEnvironment))

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId1))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId2))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId2))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId3))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId3))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId4))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId4))))
      
      when(hipEnvironments.environments).thenReturn(Seq(primaryEnvironment, secondaryEnvironment))
      
      val expected = Seq(
        buildCredentialScopes(FakeHipEnvironments.primaryEnvironment.id, clientId1),
        buildCredentialScopes(FakeHipEnvironments.primaryEnvironment.id, clientId2),
        buildCredentialScopes(FakeHipEnvironments.secondaryEnvironment.id, clientId3),
        buildCredentialScopes(FakeHipEnvironments.secondaryEnvironment.id, clientId4)
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

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any))
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
        .addCredential(FakeHipEnvironments.primaryEnvironment, buildCredential(clientId1, FakeHipEnvironments.primaryEnvironment))
        .addCredential(FakeHipEnvironments.primaryEnvironment, buildCredential(clientId2, FakeHipEnvironments.primaryEnvironment))

      val expected = IdmsException.unexpectedResponse(500)

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId1))))
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId2))(any)).thenReturn(Future.successful(Left(expected)))

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
    val service = new ApplicationsCredentialsServiceImpl(searchService, repository, idmsConnector, clock, accessRequestsService, scopeFixer, hipEnvironments)
    Fixture(searchService, repository, idmsConnector, accessRequestsService, scopeFixer, service, hipEnvironments)
  }

  private case class Fixture (
    searchService: ApplicationsSearchService,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    accessRequestsService: AccessRequestsService,
    scopeFixer: ScopeFixer,
    service: ApplicationsCredentialsService,
    hipEnvironments: HipEnvironments
  )

}

object ApplicationsCredentialsServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

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

  val primaryEnvironment: HipEnvironment = FakeHipEnvironments.primaryEnvironment
  val secondaryEnvironment: HipEnvironment = FakeHipEnvironments.secondaryEnvironment
  
}
