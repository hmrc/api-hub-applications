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
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentCaptor, ArgumentMatchers, Mockito, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Assertion, EitherValues, OptionValues}
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.AccessRequestLensOps
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Rejected}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms._
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator
import uk.gov.hmrc.http.HeaderCarrier

import java.time._
import java.util.UUID
import scala.concurrent.Future

class ApplicationsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ApplicationGenerator
    with ScalaFutures
    with OptionValues
    with EitherValues
    with TableDrivenPropertyChecks {

  val currentUser = "me@test.com"

  "registerApplication" - {
    "must build the correct application, submit it to the repository and return it" in {
      val fixture = buildFixture
      import fixture._

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

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      val applicationWithCreds = application
        .setPrimaryCredentials(Seq(primaryClientResponse.asNewHiddenCredential(clock)))
        .setSecondaryCredentials(Seq(secondaryClientResponse.asNewCredential(clock)))

      val saved = applicationWithCreds.copy(id = Some("test-id"))

      when(repository.insert(ArgumentMatchers.eq(applicationWithCreds)))
        .thenReturn(Future.successful(saved))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(saved)
      }
    }

    "must return IdmsException and not persist in MongoDb if the primary credentials fail" in {
      val fixture = buildFixture
      import fixture._

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
      val fixture = buildFixture
      import fixture._

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
      val fixture = buildFixture
      import fixture._

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
      ).setSecondaryCredentials(Seq(clientResponse.asNewCredential(clock)))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(clientResponse)))

      when(repository.insert(any()))
        .thenReturn(Future.successful(expected.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          captor.value.teamMembers mustBe expected.teamMembers
          succeed
      }
    }

    "must email team members" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(ClientResponse("test-client-id", "test-secret-1234"))))

      when(repository.insert(any()))
        .thenAnswer((application: Application) => Future.successful(application.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          val expected = captor.value.copy(id = Some("id"))
          verify(emailConnector).sendAddTeamMemberEmail(ArgumentMatchers.eq(expected))(any())
          succeed
      }
    }

    "must email application creator" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val newApplication = NewApplication("test-name", creator, Seq.empty)

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(ClientResponse("test-client-id", "test-secret-1234"))))

      when(repository.insert(any()))
        .thenAnswer((application: Application) => Future.successful(application.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          val expected = captor.value.copy(id = Some("id"))
          verify(emailConnector).sendApplicationCreatedEmailToCreator(ArgumentMatchers.eq(expected))(any())
          succeed
      }
    }

    "must not throw an exception when the Email API errors" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(ClientResponse("test-client-id", "test-secret-1234"))))

      when(repository.insert(any()))
        .thenAnswer((application: Application) => Future.successful(application.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        result =>
          result.isRight mustBe true
      }
    }
  }

  "findAll" - {
    "must return all applications from the repository" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      )

      when(repository.findAll()).thenReturn(Future.successful(applications))

      service.findAll() map {
        actual =>
          actual mustBe applications
          verify(repository).findAll()
          succeed
      }
    }
  }

  "filter" - {
    "must return all applications from the repository for named team member without enrichment" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), Seq(TeamMember("test-email-1")))
      )

      when(repository.filter("test-email-1")).thenReturn(Future.successful(applications))

      service.filter("test-email-1", enrich = false)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(applications)
          verify(repository).filter("test-email-1")
          succeed
      }
    }

    "must return all applications from the repository for named team member with enrichment" in {
      val fixture = buildFixture
      import fixture._

      val email = "test-email"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"

      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-creator-1"), Seq(TeamMember(email))).addSecondaryCredential(Credential(clientId1, LocalDateTime.now(fixture.clock), None, None))
      val application2 = Application(Some("test-id-2"), "test-name-2", Creator("test-creator-2"), Seq(TeamMember(email))).addSecondaryCredential(Credential(clientId2, LocalDateTime.now(fixture.clock), None, None))

      val scopes1 = Seq("read:app1-scope1")
      val scopes2 = Seq("read:app2-scope1", "read:app2-scope2")

      val applications = Seq(
        application1,
        application2
      )

      when(repository.filter(ArgumentMatchers.eq(email)))
        .thenReturn(Future.successful(applications))

      when(fixture.idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId1))(any()))
        .thenReturn(Future.successful(Right(scopes1.map(ClientScope(_)))))
      when(fixture.idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId2))(any()))
        .thenReturn(Future.successful(Right(scopes2.map(ClientScope(_)))))

      service.filter(email, enrich = true)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(
            Seq(
              application1.setSecondaryScopes(scopes1.map(Scope(_, Approved))),
              application2.setSecondaryScopes(scopes2.map(Scope(_, Approved)))
            )
          )
      }
    }

    "must return IdmsException when that is returned by the repository while enriching" in {
      val fixture = buildFixture
      import fixture._

      val email = "test-email"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val idmsException = IdmsException.clientNotFound(clientId2)

      val application1 = Application(Some("test-id-1"), "test-name-1", Creator("test-creator-1"), Seq(TeamMember(email))).addSecondaryCredential(Credential(clientId1, LocalDateTime.now(fixture.clock), None, None))
      val application2 = Application(Some("test-id-2"), "test-name-2", Creator("test-creator-2"), Seq(TeamMember(email))).addSecondaryCredential(Credential(clientId2, LocalDateTime.now(fixture.clock), None, None))

      val applications = Seq(
        application1,
        application2
      )

      when(repository.filter(ArgumentMatchers.eq(email)))
        .thenReturn(Future.successful(applications))

      when(fixture.idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId1))(any()))
        .thenReturn(Future.successful(Right(Seq.empty)))
      when(fixture.idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId2))(any()))
        .thenReturn(Future.successful(Left(idmsException)))

      service.filter(email, enrich = true)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(idmsException)
      }
    }
  }

  "findById" - {
    "must return the application when it exists" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val primaryClientId = "test-primary-client-id"
      val secondaryClientId = "test-secondary-client-id"
      val secondaryClientSecret = "test-secondary-secret-1234"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"
      val scope4 = "test-scope-4"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential(primaryClientId, LocalDateTime.now(fixture.clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(secondaryClientId))(any()))
        .thenReturn(Future.successful(Right(ClientResponse(secondaryClientId, secondaryClientSecret))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(secondaryClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope1), ClientScope(scope2)))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(primaryClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope3), ClientScope(scope4)))))

      val expected = application
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, LocalDateTime.now(fixture.clock), Some(secondaryClientSecret), Some("1234"))))
        .setSecondaryScopes(Seq(Scope(scope1, Approved), Scope(scope2, Approved)))
        .setPrimaryScopes(Seq(Scope(scope3, Approved), Scope(scope4, Approved)))

      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result =>
          result mustBe Right(expected)
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.findById(id, enrich = true)(HeaderCarrier()).map(
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(id))
      )
    }

    "must return IdmsException when that is returned from the IDMS connector" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setSecondaryCredentials(Seq(Credential(clientId, LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(Seq.empty)))

      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result =>
          result.left.value mustBe a[IdmsException]
      }
    }

    "must not enrich with IDMS data unless asked to" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential("test-primary-client-id", LocalDateTime.now(fixture.clock), None, None)))
        .setSecondaryCredentials(Seq(Credential("test-secondary-client-id", LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      service.findById(id, enrich = false)(HeaderCarrier()).map {
        result =>
          result mustBe Right(application)
          verifyZeroInteractions(idmsConnector)
          succeed
      }
    }
  }

  "get apps where primary env has pending scopes" - {
    val fixture = buildFixture
    import fixture._

    val appWithPrimaryPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
      .addPrimaryCredential(Credential("test-client_id", LocalDateTime.now(clock), None, None))
      .addPrimaryScope(Scope("test-scope-1", Pending))
    val appWithSecondaryPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
      .addSecondaryScope(Scope("test-scope-2", Pending))

    when(repository.findAll()).thenReturn(Future.successful(Seq(appWithPrimaryPending, appWithSecondaryPending)))

    service.getApplicationsWithPendingPrimaryScope map {
      actual =>
        actual mustBe Seq(appWithPrimaryPending.makePublic())
        verify(repository).findAll()
        succeed
    }
  }

  "delete" - {
    "must soft delete the application and leave in the repository" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      val captor: ArgumentCaptor[Application] = ArgumentCaptor.forClass(classOf[Application])
      when(repository.update(captor.capture())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))
      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).update(any())
          val captured = captor.getValue
          captured.deleted mustBe Some(Deleted(LocalDateTime.now(clock), currentUser))
          succeed
      }
    }

    "must email current user and team members" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")

      val id = "test-id"
      val application = Application(Some(id), "test-description", creator, Seq(teamMember1, teamMember2))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(application.id.get, currentUser)(HeaderCarrier()) map {
        _ =>
          verify(emailConnector).sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())
          verify(emailConnector).sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())
          succeed
      }
    }

    "must tolerate email failure and return success" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")

      val id = "test-id"
      val application = Application(Some(id), "test-description", creator, Seq(teamMember1, teamMember2))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(application.id.get, currentUser)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
          succeed
      }
    }

    "must not send email when delete operation fails" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")

      val id = "test-id"
      val application = Application(Some(id), "test-description", creator, Seq(teamMember1, teamMember2))

      val notFoundException = ApplicationNotFoundException("Not found")
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Left(notFoundException)))

      service.delete(application.id.get, currentUser)(HeaderCarrier()).map {
        result =>
          result mustBe Left(notFoundException)
          verifyNoInteractions(emailConnector)
          succeed
      }
    }

    "must delete all clients from IDMS" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId, LocalDateTime.now(fixture.clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(any())(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(idmsConnector).deleteAllClients(any())(any())
          succeed
      }
    }

    "must cancel all access requests" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId, LocalDateTime.now(fixture.clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(any())(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(accessRequestsService).cancelAccessRequests(ArgumentMatchers.eq(id))
          succeed
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(id))
      }
    }

    "must return IdmsException when that is returned from any call to the IDMS connector and not update the repo" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId1, LocalDateTime.now(fixture.clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(clientId2, LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
          verify(repository, times(0)).update(any())
          succeed
      }
    }

    "must return ApplicationsException when that is returned from any call to the access requests service and not update the repo" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId1, LocalDateTime.now(fixture.clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(clientId2, LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      val notUpdatedException = NotUpdatedException.forId(id)
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Left(notUpdatedException)))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(notUpdatedException)
          verify(repository, times(0)).update(any())
          succeed
      }
    }
  }

  "addScopes" - {

    "must add new primary scope to Application and not update idms" in {
      val fixture = buildFixture
      import fixture._

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

      service.addScopes(testAppId, Seq(newScope))(HeaderCarrier()) map {
        actual =>
          verify(repository).update(updatedApp)
          verifyZeroInteractions(idmsConnector.addClientScope(any(), any(), any())(any()))
          actual mustBe Right(())
      }

    }

    "must update idms with new secondary scope and not update application" in {
      val fixture = buildFixture
      import fixture._

      val newScope1 = NewScope("test-scope-1", Seq(Secondary))
      val newScope2 = NewScope("test-scope-2", Seq(Secondary))

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(fixture.clock), None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right({})))

      service.addScopes(testAppId, Seq(newScope1, newScope2))(HeaderCarrier()) map {
        actual =>
          verifyZeroInteractions(repository.update(any()))
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(newScope1.name))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(newScope2.name))(any())
          actual mustBe Right(())
      }

    }

    "must handle idms fail and return error for secondary scope" in {
      val fixture = buildFixture
      import fixture._

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
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(fixture.clock), None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      val exception = IdmsException("Bad thing", CallError)
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Left(exception)))
      service.addScopes(testAppId, Seq(newScope))(HeaderCarrier()) map {
        actual =>
          verifyZeroInteractions(repository.update(any()))
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          actual mustBe Left(exception)
      }

    }

    "must handle idms fail and return error for secondary scope but also process primary and update" in {
      val fixture = buildFixture
      import fixture._

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
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(fixture.clock), None, None))))
      )

      val updatedApp = app
        .addScopes(Primary, Seq(testScopeId))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))

      val exception = IdmsException("Bad thing", CallError)
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Left(exception)))
      service.addScopes(testAppId, Seq(newScope))(HeaderCarrier()) map {
        actual =>
          verify(repository).update(updatedApp)
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          actual mustBe Left(exception)
      }

    }

    "must return ApplicationNotFoundException if application not found whilst updating it with new scope" in {
      val fixture = buildFixture
      import fixture._

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

      service.addScopes(testAppId, Seq(newScopes))(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must return ApplicationNotFoundException if application not initially found" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.addScopes(testAppId, Seq(NewScope("test-name-1", Seq(Primary))))(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }
  }

  "Approving primary scope" - {
    def runTest(secret: Option[String]): Future[Assertion] = {
      val fixture = buildFixture
      import fixture._

      val testScope = "test-scope-1"
      val testClientId = "test-client-id"
      val envs = Environments(
        Environment(Seq(Scope(testScope, Pending)), Seq(Credential(testClientId, LocalDateTime.now(fixture.clock), None, secret))),
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
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScope))(any())).thenReturn(Future(Right(())))

      service.approvePrimaryScope(testAppId, testScope)(HeaderCarrier()) map {
        actual => {
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScope))(any())
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
      val fixture = buildFixture
      import fixture._

      val testScope = "test-scope-1"
      val testClientId = "test-client-id"
      val envs = Environments(
        Environment(Seq(Scope(testScope, Pending)), Seq(Credential(testClientId, LocalDateTime.now(fixture.clock), None, None))),
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
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.approvePrimaryScope(testAppId, "test-name-2")(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must return bad application exception when trying to APPROVE scope when scope exists but is not PENDING" in {
      val fixture = buildFixture
      import fixture._

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
      val fixture = buildFixture
      import fixture._

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

      service.approvePrimaryScope(testAppId, scopeName)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(app, InvalidPrimaryScope))
      }
    }

  }

  "create primary secret" - {
    "must map success result" in {
      val fixture = buildFixture
      import fixture._

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
        environments = Environments(primary = Environment(scopes = Seq(), credentials = Seq(Credential(clientId = clientId, created = LocalDateTime.now(fixture.clock), clientSecret = None, secretFragment = None))),
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
      val fixture = buildFixture
      import fixture._

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
        environments = Environments(primary = Environment(scopes = Seq(), credentials = Seq(Credential(clientId = clientId, created = LocalDateTime.now(fixture.clock), clientSecret = None, secretFragment = None))),
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
      val fixture = buildFixture
      import fixture._

      val applicationId = "app-1234"

      when(repository.findById(applicationId)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          val expected = Left(ApplicationNotFoundException.forId(applicationId))
          actual mustBe expected
      }
    }

    "must map handle application does not have a hidden master primary credential" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "app-1234"
      val application = Application(
        id = Some(applicationId),
        name = "an app",
        created = LocalDateTime.now(clock),
        createdBy = Creator("created by"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(),
        environments = Environments()
      ).addPrimaryCredential(Credential("test-client-id", LocalDateTime.now(clock), None, Some("test-fragment")))

      when(repository.findById(applicationId)).thenReturn(Future.successful(Right(application)))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials))
      }
    }

  }

  "addApi" - {

    "must update the application with the new Api" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val updatedApp = app.copy(
        apis = Seq(Api(api.id, api.endpoints)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(ClientResponse(testClientId, "Shhh!"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq.empty)))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(ArgumentMatchers.eq(updatedApp))
          actual mustBe Right(())
      }
    }

    "must update idms with new secondary scope when additional scopes are required" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(clock), None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right({})))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(ClientResponse(testClientId, "Shhh!"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq.empty)))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1", "test-scope-2")))(HeaderCarrier()).map {
        actual =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq("test-scope-1"))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq("test-scope-2"))(any())

          actual mustBe Right(())
      }
    }

    "must not update idms with new secondary scope when additional scopes are already present" in {
      val fixture = buildFixture
      import fixture._

      val clientScope1 = ClientScope("test-scope-1")
      val clientScope2 = ClientScope("test-scope-2")

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(clock), None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right({})))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(ClientResponse(testClientId, "Shhh!"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq(clientScope1, clientScope2))))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1", "test-scope-2")))(HeaderCarrier()) map {
        actual =>
          verify(idmsConnector, never).addClientScope(any(), any(), any())(any())
          actual mustBe Right(())
      }

    }

    "must handle idms fail and return error for secondary scope" in {
      val fixture = buildFixture
      import fixture._

      val testScopeId = "test-scope-1"

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(clock), None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      val exception = IdmsException("Bad thing", CallError)
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Left(exception)))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(ClientResponse(testClientId, "Shhh!"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq.empty)))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1")))(HeaderCarrier()) map {
        actual =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          actual mustBe Left(exception)
      }

    }

    "must return ApplicationNotFoundException if application not found whilst updating it with new api" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val updatedApp = app.copy(
        apis = Seq(Api(api.id, api.endpoints)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(ClientResponse(testClientId, "Shhh!"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq.empty)))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must return ApplicationNotFoundException if application not initially found" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1")))(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }
  }

  "addCredential" - {

    "must create a new secondary credential and copy the previous secondary master scopes" in {
      val fixture = buildFixture
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
        environments = Environments(primary = Environment(), secondary = Environment(Seq(Scope(scopeName, Approved)), Seq(existingCredential)))
      )

      val existingClientResponse = ClientResponse(oldTestClientId, oldSecret)
      val newClientResponse = ClientResponse(newTestClientId, newSecret)

      val expectedClient = Client(app.name, app.name)
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(oldTestClientId))(any())).thenReturn(Future.successful(Right(existingClientResponse)))
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), any())(any())).thenReturn(Future.successful(Right(newClientResponse)))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(oldTestClientId))(any())).thenReturn(Future.successful(Right(Seq(ClientScope(scopeName)))))
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(newTestClientId))(any())).thenReturn(Future.successful(Right(newClientResponse)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addSecondaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Secondary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(expectedClient))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must create a new primary credential and copy the previous primary master scopes where the existing credential is not hidden" in {
      val fixture = buildFixture
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
        environments = Environments(primary = Environment(Seq(Scope(scopeName, Approved)), Seq(existingCredential)), secondary = Environment())
      )

      val newClientResponse = ClientResponse(newTestClientId, newSecret)
      val expectedClient = Client(app.name, app.name)

      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), any())(any())).thenReturn(Future.successful(Right(newClientResponse)))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(oldTestClientId))(any())).thenReturn(Future.successful(Right(Seq(ClientScope(scopeName)))))
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(newTestClientId))(any())).thenReturn(Future.successful(Right(newClientResponse)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addPrimaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(expectedClient))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must update existing primary credential and set a secret fragment where the existing credential is hidden" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val scopeName = "test-scope"

      val existingHiddenCredential = Credential(testClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, None)
      val newSecret = "test-secret-1234"

      val expectedCredential = Credential(testClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"))
      val clientResponse = ClientResponse(testClientId, newSecret)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(Seq(Scope(scopeName, Approved)), Seq(existingHiddenCredential)), secondary = Environment())
      )

      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq(ClientScope(scopeName)))))
      when(idmsConnector.newSecret(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Secret(newSecret))))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(clientResponse)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      val updatedApp = app.setPrimaryCredentials(Seq(expectedCredential))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector, Mockito.times(0)).createClient(ArgumentMatchers.eq(Primary), any())(any())
          verify(idmsConnector, Mockito.times(0)).addClientScope(ArgumentMatchers.eq(Primary), any(), any())(any())
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must return ApplicationCredentialLimitException if there are already 5 credentials" in {
      val fixture = buildFixture
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

      when(idmsConnector.fetchClientScopes(any(), any())(any())).thenReturn(Future.successful(Right(Seq.empty)))
      when(repository.findById(any())).thenReturn(Future.successful(Right(application)))

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

        when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
        when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Right(())))
        when(repository.update(any())).thenReturn(Future.successful(Right(())))

        service.deleteCredential(applicationId, environmentName, clientId)(HeaderCarrier()).map {
          result =>
            verify(idmsConnector).deleteClient(ArgumentMatchers.eq(environmentName), ArgumentMatchers.eq(clientId))(any())
            result mustBe Right(())
        }
      }
    }

    "must update the application in the repository" in {
      val fixture = buildFixture
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

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          val updated = application
            .copy(lastUpdated = LocalDateTime.now(clock))
            .removePrimaryCredential(clientId)
          verify(repository).update(ArgumentMatchers.eq(updated))
          result mustBe Right(())
      }
    }

    "must succeed when IDMS returns ClientNotFound" in {
      val fixture = buildFixture
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

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientId))))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return CredentialNotFoundException when the credential does not exist in the application" in {
      val fixture = buildFixture
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

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(CredentialNotFoundException.forClientId(clientId))
      }
    }

    "must return IdmsException when this is returned from IDMS" in {
      val fixture = buildFixture
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

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }

    "must return ApplicationCredentialLimitException when an attempt is made to delete the last credential" in {
      val fixture = buildFixture
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

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, Primary))
      }
    }
  }

  "addPrimaryAccess" - {
    "must add the new scopes to all primary credentials" in {
      val fixture = buildFixture
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

      when(repository.findById(any())).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1) ,ArgumentMatchers.eq(scope1))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1) ,ArgumentMatchers.eq(scope2))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1) ,ArgumentMatchers.eq(scope3))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId2) ,ArgumentMatchers.eq(scope1))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId2) ,ArgumentMatchers.eq(scope2))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId2) ,ArgumentMatchers.eq(scope3))(any())
          verifyNoMoreInteractions(idmsConnector)
          result mustBe Right(())
      }
    }

    "must return an IdmsException if any IDMS call fails but others succeed" in {
      val fixture = buildFixture
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

      when(repository.findById(any())).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.addClientScope(any(), any(), ArgumentMatchers.eq(scope1))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), ArgumentMatchers.eq(scope2))(any())).thenReturn(Future.successful(Left(exception)))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }

    "must return application not found exception when it does not exist" in {
      val fixture = buildFixture
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

      when(repository.findById(any())).thenReturn(Future.successful(Left(exception)))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }
  }

  private case class Fixture(
    clock: Clock,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    emailConnector: EmailConnector,
    service: ApplicationsService,
    accessRequestsService: AccessRequestsService
  )

  private def buildFixture: Fixture = {
    val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val repository: ApplicationsRepository = mock[ApplicationsRepository]
    val idmsConnector: IdmsConnector = mock[IdmsConnector]
    val emailConnector: EmailConnector = mock[EmailConnector]
    val accessRequestsService: AccessRequestsService = mock[AccessRequestsService]
    val service: ApplicationsService = new ApplicationsService(repository, clock, idmsConnector, emailConnector, accessRequestsService)
    Fixture(clock, repository, idmsConnector, emailConnector, service, accessRequestsService)
  }

}
