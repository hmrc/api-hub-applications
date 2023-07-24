/*
 * Copyright 2023 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{Assertion, EitherValues, OptionValues}
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import java.util.UUID
import scala.concurrent.Future

class ApplicationsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ApplicationGenerator
    with ScalaFutures
    with OptionValues
    with EitherValues {

  private val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "registerApplication" - {
    "must build the correct application and submit it to the repository" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication(
        "test-name",
        Creator(email = teamMember1.email),
        Seq(teamMember1, teamMember2)
      )

      val application = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = newApplication.createdBy,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2),
        environments = Environments()
      )

      val primaryClientResponse = ClientResponse("primary-client-id", "test-secret-1234")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(primaryClientResponse)))

      val secondaryClientResponse = ClientResponse("secondary-client-id", "test-secret-5678")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(secondaryClientResponse)))

      val applicationWithCreds = application
        .setPrimaryCredentials(Seq(Credential(primaryClientResponse.clientId, None, None)))
        .setSecondaryCredentials(Seq(secondaryClientResponse.asCredential()))

      val expected = applicationWithCreds.copy(id = Some("test-id"))

      when(repository.insert(ArgumentMatchers.eq(applicationWithCreds)))
        .thenReturn(Future.successful(expected))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(expected)
      }
    }

    "must return IdmsException and not persist in MongoDb if the primary credentials fail" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val newApplication = NewApplication(
        "test-name",
        Creator(email = "test-email"),
        Seq.empty
      )

      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

      val secondaryClientResponse = ClientResponse("secondary-client-id", "test-secret-5678")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(secondaryClientResponse)))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          verifyZeroInteractions(repository.insert(any()))
          succeed
      }
    }

    "must return IdmsException and not persist in MongoDb if the secondary credentials fail" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val newApplication = NewApplication(
        "test-name",
        Creator(email = "test-email"),
        Seq.empty
      )

      val primaryClientResponse = ClientResponse("primary-client-id", "test-secret-1234")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(primaryClientResponse)))

      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          verifyZeroInteractions(repository.insert(any()))
          succeed
      }
    }

    "must add the creator as a team member if they are not already one" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      val clientResponse = ClientResponse("test-client-id", "test-secret-1234")

      val expected = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = creator,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2, TeamMember(creator.email)),
        environments = Environments()
      ).setSecondaryCredentials(Seq(clientResponse.asCredential()))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(clientResponse)))

      when(repository.insert(any()))
        .thenReturn(Future.successful(expected.copy(id = Some("id"))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          captor.value.teamMembers mustBe expected.teamMembers
          succeed
      }
    }
  }

  "findAll" - {
    "must return all applications from the repository" in {
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      )

      val repository = mock[ApplicationsRepository]
      when(repository.findAll()).thenReturn(Future.successful(applications))

      val idmsConnector = mock[IdmsConnector]

      val service = new ApplicationsService(repository, clock, idmsConnector)
      service.findAll() map {
        actual =>
          actual mustBe applications
          verify(repository).findAll()
          succeed
      }
    }

    "must return all applications from the repository for named team member" in {
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
      )

      val repository = mock[ApplicationsRepository]
      when(repository.filter("test-email-1")).thenReturn(Future.successful(applications))

      val idmsConnector = mock[IdmsConnector]

      val service = new ApplicationsService(repository, clock, idmsConnector)
      service.filter("test-email-1") map {
        actual =>
          actual mustBe applications
          verify(repository).filter("test-email-1")
          succeed
      }
    }

  }

  "findById" - {
    "must return the application when it exists" in {
      val id = "test-id"
      val primaryClientId = "test-primary-client-id"
      val secondaryClientId = "test-secondary-client-id"
      val secondaryClientSecret = "test-secondary-secret-1234"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"
      val scope4 = "test-scope-4"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential(primaryClientId, None, None)))
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, None, None)))

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(secondaryClientId))(any()))
        .thenReturn(Future.successful(Right(ClientResponse(secondaryClientId, secondaryClientSecret))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(secondaryClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope1), ClientScope(scope2)))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(primaryClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope3), ClientScope(scope4)))))

      val expected = application
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, Some(secondaryClientSecret), Some("1234"))))
        .setSecondaryScopes(Seq(Scope(scope1, Approved), Scope(scope2, Approved)))
        .setPrimaryScopes(Seq(Scope(scope3, Approved), Scope(scope4, Approved)))

      val service = new ApplicationsService(repository, clock, idmsConnector)
      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result =>
          result mustBe Right(expected)
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val id = "test-id"

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)
      service.findById(id, enrich = true)(HeaderCarrier()).map(
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(id))
      )
    }

    "must return IdmsException when that is returned from the IDMS connector" in {
      val id = "test-id"
      val clientId = "test-client-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setSecondaryCredentials(Seq(Credential(clientId, None, None)))

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(Seq.empty)))

      val service = new ApplicationsService(repository, clock, idmsConnector)
      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result =>
          result.left.value mustBe a[IdmsException]
      }
    }

    "must not enrich with IDMS data unless asked to" in {
      val id = "test-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential("test-primary-client-id", None, None)))
        .setSecondaryCredentials(Seq(Credential("test-secondary-client-id", None, None)))

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      val idmsConnector = mock[IdmsConnector]

      val service = new ApplicationsService(repository, clock, idmsConnector)
      service.findById(id, enrich = false)(HeaderCarrier()).map {
        result =>
          result mustBe Right(application)
          verifyZeroInteractions(idmsConnector)
          succeed
      }
    }
  }

  "get apps where primary env has pending scopes" - {
    val appWithPrimaryPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
      .addPrimaryScope(Scope("test-scope-1", Pending))
    val appWithSecondaryPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
      .addSecondaryScope(Scope("test-scope-2", Pending))

    val repository = mock[ApplicationsRepository]
    when(repository.findAll()).thenReturn(Future.successful(Seq(appWithPrimaryPending, appWithSecondaryPending)))

    val idmsConnector = mock[IdmsConnector]

    val service = new ApplicationsService(repository, clock, idmsConnector)
    service.getApplicationsWithPendingPrimaryScope map {
      actual =>
        actual mustBe Seq(appWithPrimaryPending)
        verify(repository).findAll()
        succeed
    }
  }

  "delete" - {
    "must delete the application from the repository" in {
      val id = "test-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))

      val idmsConnector = mock[IdmsConnector]

      val service = new ApplicationsService(repository, clock, idmsConnector)

      service.delete(id)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(any())
          succeed
      }
    }

    "must delete the primary credential from IDMS" in {
      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId, None, None)))

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(())))

      val service = new ApplicationsService(repository, clock, idmsConnector)

      service.delete(id)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(any())
          verify(idmsConnector).deleteClient(any(), any())(any())
          succeed
      }
    }

    "must delete the secondary credential from IDMS" in {
      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setSecondaryCredentials(Seq(Credential(clientId, None, None)))

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(())))

      val service = new ApplicationsService(repository, clock, idmsConnector)

      service.delete(id)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(any())
          verify(idmsConnector).deleteClient(any(), any())(any())
          succeed
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val id = "test-id"
      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      val idmsConnector = mock[IdmsConnector]

      val service = new ApplicationsService(repository, clock, idmsConnector)

      service.delete(id)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(id))
      }
    }

    "must return IdmsException when that is returned from any call to the IDMS connector" in {
      val id = "test-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId1, None, None)))
        .setSecondaryCredentials(Seq(Credential(clientId2, None, None)))

      val repository = mock[ApplicationsRepository]
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1))(any()))
        .thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId2))(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      val service = new ApplicationsService(repository, clock, idmsConnector)

      service.delete(id)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
          verify(repository, times(0)).delete(any())
          succeed
      }
    }
  }

  "addScopes" - {

    "must add new primary scope to Application and not update idms" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val newScope = NewScope("test-name-1", Seq(Primary))

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val updatedApp = app
        .addScopes(Primary, Seq("test-name-1"))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))

      service.addScope(testAppId, newScope)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(updatedApp)
          verifyZeroInteractions(idmsConnector.addClientScope(any(), any(), any())(any()))
          actual mustBe Right(())
      }

    }

    "must update idms with new secondary scope and not update application" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testScopeId = "test-name-1"
      val newScope = NewScope(testScopeId, Seq(Secondary))

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right({})))
      service.addScope(testAppId, newScope)(HeaderCarrier()) map {
        actual =>
          verifyZeroInteractions(repository.update(any()))
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          actual mustBe Right(())
      }

    }

    "must handle idms fail and return error for secondary scope" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testScopeId = "test-name-1"
      val newScope = NewScope(testScopeId, Seq(Secondary))

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      val exception = IdmsException("Bad thing", CallError)
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Left(exception)))
      service.addScope(testAppId, newScope)(HeaderCarrier()) map {
        actual =>
          verifyZeroInteractions(repository.update(any()))
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          actual mustBe Left(exception)
      }

    }

    "must handle idms fail and return error for secondary scope but also process primary and update" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testScopeId = "test-name-1"
      val newScope = NewScope(testScopeId, Seq(Primary, Secondary))

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, None, None))))
      )

      val updatedApp = app
        .addScopes(Primary, Seq(testScopeId))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))

      val exception = IdmsException("Bad thing", CallError)
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Left(exception)))
      service.addScope(testAppId, newScope)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(updatedApp)
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          actual mustBe Left(exception)
      }

    }

    "must return ApplicationNotFoundException if application not found whilst updating it with new scope" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val newScopes = NewScope("test-name-1", Seq(Primary))

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val updatedApp = app
        .addScopes(Primary, Seq("test-name-1"))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.addScope(testAppId, newScopes)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must return ApplicationNotFoundException if application not initially found" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.addScope(testAppId, NewScope("test-name-1", Seq(Primary)))(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }
  }

  "Approving primary scope" - {
    def runTest(secret: Option[String]): Future[Assertion] = {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testScope = "test-scope-1"
      val testClientId = "test-client-id"
      val envs = Environments(
        Environment(Seq(Scope(testScope, Pending)), Seq(Credential(testClientId, None, secret))),
        Environment(Seq.empty, Seq.empty)
      )

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = testAppId,
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = envs
      )

      val updatedApp = app.setPrimaryScopes(Seq())
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScope) )(any())).thenReturn(Future(Right(())))

      service.approvePrimaryScope(testAppId, testScope)(HeaderCarrier())  map {
        actual => {
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScope) )(any())
          actual mustBe Right(())
        }
      }
    }

    "must update idms and delete primary scope for a newly created application" in {
      runTest(None)
    }

    "must update idms and delete primary scope for a mature application with primary secret" in {
      runTest(Some("1234"))
    }

    "must not delete primary scope if idms update fails" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testScope = "test-scope-1"
      val testClientId = "test-client-id"
      val envs = Environments(
        Environment(Seq(Scope(testScope, Pending)), Seq(Credential(testClientId, None, None))),
        Environment(Seq.empty, Seq.empty)
      )

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = testAppId,
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = envs
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScope))(any())).thenReturn(Future(Left(IdmsException(":(", CallError))))

      service.approvePrimaryScope(testAppId, testScope)(HeaderCarrier()) map {
        actual => {
          verifyZeroInteractions(repository.update(any()))
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScope))(any())
          actual mustBe Left(IdmsException(":(", CallError))
        }
      }
    }

    "must return not found exception if application does not exist" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.approvePrimaryScope(testAppId, "test-name-2")(HeaderCarrier())  map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must return bad application exception when trying to APPROVE scope when scope exists but is not PENDING" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)
      val scopeName = "test-scope-1"

      val envs = Environments(
        Environment(Seq(Scope("test-scope-1", Denied)), Seq.empty),
        Environment(Seq.empty, Seq.empty)
      )

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = envs
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      service.approvePrimaryScope(testAppId, scopeName)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(app, InvalidPrimaryScope))
      }
    }

    "must return bad application exception when scope does not exist" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)
      val scopeName = "test-scope-1"

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      service.approvePrimaryScope(testAppId, scopeName)(HeaderCarrier())  map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(app, InvalidPrimaryScope))
      }
    }

  }

  "create primary secret" - {
    "must map success result" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication(
        "test-name",
        Creator(email = teamMember1.email),
        Seq(teamMember1, teamMember2)
      )

      val applicationId = "app-1234"
      val clientId = "client-6789"
      val secret = "test-secret-1234"

      val application = Application(
        id = Some(applicationId),
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = newApplication.createdBy,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2),
        environments = Environments(primary = Environment(scopes = Seq(), credentials = Seq(Credential(clientId = clientId, clientSecret = None, secretFragment = None))),
          secondary = Environment()
        )
      )

      when(repository.findById(applicationId)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      val secretResponse = Secret(secret)
      when(idmsConnector.newSecret(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(secretResponse)))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(secretResponse)
      }
    }

    "must map handle idms fail" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication(
        "test-name",
        Creator(email = teamMember1.email),
        Seq(teamMember1, teamMember2)
      )

      val applicationId = "app-1234"
      val clientId = "client-6789"

      val application = Application(
        id = Some(applicationId),
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = newApplication.createdBy,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2),
        environments = Environments(primary = Environment(scopes = Seq(), credentials = Seq(Credential(clientId = clientId, clientSecret = None, secretFragment = None))),
          secondary = Environment()
        )
      )

      when(repository.findById(applicationId)).thenReturn(Future.successful(Right(application)))

      val expected = Left(IdmsException("bad thing", CallError))
      when(idmsConnector.newSecret(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(expected))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          actual mustBe expected
      }
    }

    "must handle application not found" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val applicationId = "app-1234"

      when(repository.findById(applicationId)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          val expected = Left(ApplicationNotFoundException.forId(applicationId))
          actual mustBe expected
      }
    }

    "must map handle application has no primary credentials" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val applicationId = "app-1234"
      val application = Application(
        id = Some(applicationId),
        name = "an app",
        created = LocalDateTime.now(clock),
        createdBy = Creator("created by"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(),
        environments = Environments()
      )

      when(repository.findById(applicationId)).thenReturn(Future.successful(Right(application)))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials))
      }
    }

    "must handle application has no client id in credentials" in {
      val repository = mock[ApplicationsRepository]
      val idmsConnector = mock[IdmsConnector]
      val service = new ApplicationsService(repository, clock, idmsConnector)

      val applicationId = "app-1234"
      val application = Application(
        id = Some(applicationId),
        name = "an app",
        created = LocalDateTime.now(clock),
        createdBy = Creator("created by"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(),
        environments = Environments(primary = Environment(scopes = Seq(), credentials = Seq(Credential(clientId = null, clientSecret = None, secretFragment = None))),
          secondary = Environment()
        )
      )

      when(repository.findById(applicationId)).thenReturn(Future.successful(Right(application)))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials))
      }
    }

  }

}
