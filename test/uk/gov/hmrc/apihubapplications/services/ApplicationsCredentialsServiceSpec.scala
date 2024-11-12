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
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationCredentialLimitException, ApplicationNotFoundException, CredentialNotFoundException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.http.HeaderCarrier

import java.time.*
import scala.concurrent.Future

class ApplicationsCredentialsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with TableDrivenPropertyChecks with EitherValues {

  import ApplicationsCredentialsServiceSpec.*

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
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), Some(oldSecret), Some("9876"))
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some("test-secret-1234"), Some("1234"))

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
      when(idmsConnector.createClient(eqTo(Secondary), any)(any)).thenReturn(Future.successful(Right(newClientResponse)))
      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))

      val updatedApp = app
        .addSecondaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty))(any)).thenReturn(Future.successful(Right(updatedApp)))

      service.addCredential(testAppId, Secondary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(Secondary), eqTo(expectedClient))(any)
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
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, Some("9876"))
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"))

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

      when(idmsConnector.createClient(eqTo(Primary), any)(any)).thenReturn(Future.successful(Right(newClientResponse)))
      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addPrimaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty))(any)).thenReturn(Future.successful(Right(updatedApp)))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(Primary), eqTo(expectedClient))(any)
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

      val existingHiddenCredential = Credential(testClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, None)
      val newSecret = "test-secret-1234"

      val expectedCredential = Credential(testClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"))

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(Seq(Scope(scopeName)), Seq(existingHiddenCredential)), secondary = Environment())
      )

      when(idmsConnector.newSecret(eqTo(Primary), eqTo(testClientId))(any)).thenReturn(Future.successful(Right(Secret(newSecret))))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app.setPrimaryCredentials(Seq(expectedCredential))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(scopeFixer.fix(eqTo(updatedApp), eqTo(Seq.empty))(any)).thenReturn(Future.successful(Right(updatedApp)))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector, times(0)).createClient(eqTo(Primary), any)(any)
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
      ).setPrimaryCredentials(
        (1 to 5).map(i => Credential(s"test-client-$i", LocalDateTime.now(clock), None, None))
      )

      when(idmsConnector.fetchClientScopes(any, any)(any)).thenReturn(Future.successful(Right(Seq.empty)))
      when(searchService.findById(any, any)(any)).thenReturn(Future.successful(Right(application)))

      service.addCredential(applicationId, Primary)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, Primary))
      }
    }
  }

  "deleteCredential" - {
    "must delete the correct credential from the correct environment" in {
      val environmentNames = Table(
        "Environment Name",
        Primary,
        Secondary
      )

      val applicationId = "test-id"
      val clientId = "test-client-id"

      forAll(environmentNames) {(environmentName: EnvironmentName) =>
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
          .addCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None), environmentName)
          .addCredential(Credential(clientId, LocalDateTime.now(clock), None, None), environmentName)

        when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
        when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
        when(repository.update(any)).thenReturn(Future.successful(Right(())))

        service.deleteCredential(applicationId, environmentName, clientId)(HeaderCarrier()).map {
          result =>
            verify(idmsConnector).deleteClient(eqTo(environmentName), eqTo(clientId))(any)
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
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential("test-primary-client-id", LocalDateTime.now(clock), None, None))
        .addSecondaryCredential(Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          val updated = application
            .copy(lastUpdated = LocalDateTime.now(clock))
            .removePrimaryCredential(clientId)
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
        .addPrimaryCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientId))))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
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

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
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
        .addPrimaryCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
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
        .addPrimaryCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
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
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, Primary))
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
        .addPrimaryCredential(buildCredential(clientId1))
        .addPrimaryCredential(buildCredential(clientId2))
        .addSecondaryCredential(buildCredential(clientId3))
        .addSecondaryCredential(buildCredential(clientId4))

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(clientId1))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId1))))
      when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(clientId2))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId2))))
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(clientId3))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId3))))
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(clientId4))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId4))))

      val expected = Seq(
        buildCredentialScopes(Primary, clientId1),
        buildCredentialScopes(Primary, clientId2),
        buildCredentialScopes(Secondary, clientId3),
        buildCredentialScopes(Secondary, clientId4)
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
        .addPrimaryCredential(buildCredential(clientId1))
        .addPrimaryCredential(buildCredential(clientId2))

      val expected = IdmsException.unexpectedResponse(500)

      when(searchService.findById(eqTo(application.safeId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(clientId1))(any)).thenReturn(Future.successful(Right(buildClientScopes(clientId1))))
      when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(clientId2))(any)).thenReturn(Future.successful(Left(expected)))

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
    val service = new ApplicationsCredentialsServiceImpl(searchService, repository, idmsConnector, clock, accessRequestsService, scopeFixer)
    Fixture(searchService, repository, idmsConnector, accessRequestsService, scopeFixer, service)
  }

  private case class Fixture (
    searchService: ApplicationsSearchService,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    accessRequestsService: AccessRequestsService,
    scopeFixer: ScopeFixer,
    service: ApplicationsCredentialsService
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

  def buildCredential(clientId: String): Credential = {
    Credential(clientId = clientId, created = LocalDateTime.now(clock), clientSecret = None, secretFragment = None)
  }

  def buildClientScopes(clientId: String): Seq[ClientScope] = {
    Seq(
      ClientScope(s"$clientId-scope-1"),
      ClientScope(s"$clientId-scope-2")
    )
  }

  def buildCredentialScopes(environmentName: EnvironmentName, clientId: String): CredentialScopes = {
    CredentialScopes(
      environmentName = environmentName,
      clientId = clientId,
      created = LocalDateTime.now(clock),
      scopes = buildClientScopes(clientId).map(_.clientScopeId)
    )
  }

}
