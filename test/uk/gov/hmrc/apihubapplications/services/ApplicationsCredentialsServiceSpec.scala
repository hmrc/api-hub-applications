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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Rejected}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses._
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Credential, Environment, EnvironmentName, Environments, Primary, Scope, Secondary, TeamMember}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationCredentialLimitException, ApplicationNotFoundException, CredentialNotFoundException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, Secret}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Duration, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsCredentialsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with ArgumentMatchersSugar with TableDrivenPropertyChecks {

  import ApplicationsCredentialsServiceSpec._

  "addCredential" - {

    "must create a new secondary credential and copy the previous secondary master scopes" in {
      val fixture = buildFixture()
      import fixture._

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
      when(idmsConnector.addClientScope(eqTo(Secondary), eqTo(newTestClientId), eqTo(scopeName))(any)).thenReturn(Future.successful(Right(())))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addSecondaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Secondary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(Secondary), eqTo(expectedClient))(any)
          verify(idmsConnector).addClientScope(eqTo(Secondary), eqTo(newTestClientId), eqTo(scopeName))(any)
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must create a new primary credential and copy the previous primary master scopes where the existing credential is not hidden" in {
      val fixture = buildFixture()
      import fixture._

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
      when(idmsConnector.addClientScope(eqTo(Primary), eqTo(newTestClientId), eqTo(scopeName))(any)).thenReturn(Future.successful(Right(())))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addPrimaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(eqTo(Primary), eqTo(expectedClient))(any)
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(newTestClientId), eqTo(scopeName))(any)
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must update existing primary credential and set a secret fragment where the existing credential is hidden" in {
      val fixture = buildFixture()
      import fixture._

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

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector, times(0)).createClient(eqTo(Primary), any)(any)
          verify(idmsConnector, times(0)).addClientScope(eqTo(Primary), any, any)(any)
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must return ApplicationCredentialLimitException if there are already 5 credentials" in {
      val fixture = buildFixture()
      import fixture._

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
        val fixture = buildFixture()
        import fixture._

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
      val fixture = buildFixture()
      import fixture._

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
      val fixture = buildFixture()
      import fixture._

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
      val fixture = buildFixture()
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return CredentialNotFoundException when the credential does not exist in the application" in {
      val fixture = buildFixture()
      import fixture._

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
      val fixture = buildFixture()
      import fixture._

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
      val fixture = buildFixture()
      import fixture._

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

  "addPrimaryAccess" - {
    "must add the new scopes to all primary credentials" in {
      val fixture = buildFixture()
      import fixture._

      val applicationId = "test-application-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"

      val accessRequest = AccessRequest(
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Rejected,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(clock),
        requestedBy = "test-requested-by"
      )
        .addEndpoint("test-method-1", "test-path-1", Seq(scope1, scope2))
        .addEndpoint("test-method-2", "test-path-2", Seq(scope2, scope3))

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setPrimaryCredentials(
        Seq(
          Credential(clientId1, LocalDateTime.now(clock), None, None),
          Credential(clientId2, LocalDateTime.now(clock), None, None)
        )
      )

      when(searchService.findById(any, any)(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1) ,eqTo(scope1))(any)
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1) ,eqTo(scope2))(any)
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1) ,eqTo(scope3))(any)
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId2) ,eqTo(scope1))(any)
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId2) ,eqTo(scope2))(any)
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId2) ,eqTo(scope3))(any)
          verifyNoMoreInteractions(idmsConnector)
          result mustBe Right(())
      }
    }

    "must return an IdmsException if any IDMS call fails but others succeed" in {
      val fixture = buildFixture()
      import fixture._

      val applicationId = "test-application-id"
      val clientId = "test-client-id"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"

      val accessRequest = AccessRequest(
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Rejected,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(clock),
        requestedBy = "test-requested-by"
      )
        .addEndpoint("test-method-1", "test-path-1", Seq(scope1, scope2))

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setPrimaryCredentials(
        Seq(
          Credential(clientId, LocalDateTime.now(clock), None, None)
        )
      )

      val exception = IdmsException("test-message", CallError)

      when(searchService.findById(any, any)(any)).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.addClientScope(any, any, eqTo(scope1))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any, any, eqTo(scope2))(any)).thenReturn(Future.successful(Left(exception)))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }

    "must return application not found exception when it does not exist" in {
      val fixture = buildFixture()
      import fixture._

      val applicationId = "test-application-id"

      val accessRequest = AccessRequest(
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Rejected,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(clock),
        requestedBy = "test-requested-by"
      )

      val exception = ApplicationNotFoundException.forId(applicationId)

      when(searchService.findById(any, any)(any)).thenReturn(Future.successful(Left(exception)))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }
  }

  private case class Fixture(
    searchService: ApplicationsSearchService,
    idmsConnector: IdmsConnector,
    repository: ApplicationsRepository,
    service: ApplicationsCredentialsService
  )

  private def buildFixture(): Fixture = {
    val searchService = mock[ApplicationsSearchService]
    val idmsConnector = mock[IdmsConnector]
    val repository = mock[ApplicationsRepository]
    val service = new ApplicationsCredentialsServiceImpl(searchService, idmsConnector, repository, clock)
    Fixture(searchService, idmsConnector, repository, service)
  }

}

object ApplicationsCredentialsServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

}
