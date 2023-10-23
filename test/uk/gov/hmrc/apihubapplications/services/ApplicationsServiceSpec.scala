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
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{Assertion, EitherValues, OptionValues}
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms._
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
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

  val currentUser = "me@test.com"

  "registerApplication" - {
    "must build the correct application and submit it to the repository" in {
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

      val expected = applicationWithCreds.copy(id = Some("test-id"))

      when(repository.insert(ArgumentMatchers.eq(applicationWithCreds)))
        .thenReturn(Future.successful(expected))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(expected)
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
      .addPrimaryScope(Scope("test-scope-1", Pending))
    val appWithSecondaryPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
      .addSecondaryScope(Scope("test-scope-2", Pending))

    when(repository.findAll()).thenReturn(Future.successful(Seq(appWithPrimaryPending, appWithSecondaryPending)))

    service.getApplicationsWithPendingPrimaryScope map {
      actual =>
        actual mustBe Seq(appWithPrimaryPending)
        verify(repository).findAll()
        succeed
    }
  }

  "delete" - {
    "must delete the application from the repository" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(any())
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
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))

      service.delete(application.id.get, currentUser)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).delete(captor.capture)
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
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))

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

    "must delete the primary credential from IDMS" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId, LocalDateTime.now(fixture.clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))

      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(any())
          verify(idmsConnector).deleteClient(any(), any())(any())
          succeed
      }
    }

    "must delete the secondary credential from IDMS" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setSecondaryCredentials(Seq(Credential(clientId, LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.delete(ArgumentMatchers.eq(application))).thenReturn(Future.successful(Right(())))

      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(any())
          verify(idmsConnector).deleteClient(any(), any())(any())
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

    "must return IdmsException when that is returned from any call to the IDMS connector" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId1, LocalDateTime.now(fixture.clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(clientId2, LocalDateTime.now(fixture.clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))

      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1))(any()))
        .thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId2))(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
          verify(repository, times(0)).delete(any())
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

    "must map handle application has no primary credentials" in {
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
      )

      when(repository.findById(applicationId)).thenReturn(Future.successful(Right(application)))

      service.createPrimarySecret(applicationId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials))
      }
    }

    "must handle application has no client id in credentials" in {
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
        environments = Environments(primary = Environment(scopes = Seq(), credentials = Seq(Credential(clientId = null, created = LocalDateTime.now(fixture.clock), clientSecret = None, secretFragment = None))),
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
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, None, None))))
      )

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right({})))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(ClientResponse(testClientId, "Shhh!"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq.empty)))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1","test-scope-2")))(HeaderCarrier()).map {
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
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, None, None))))
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
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, None, None))))
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

  private case class Fixture(
                              clock: Clock,
                              repository: ApplicationsRepository,
                              idmsConnector: IdmsConnector,
                              emailConnector: EmailConnector,
                              service: ApplicationsService
                            )

  private def buildFixture: Fixture = {
    val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val repository: ApplicationsRepository = mock[ApplicationsRepository]
    val idmsConnector: IdmsConnector = mock[IdmsConnector]
    val emailConnector: EmailConnector = mock[EmailConnector]
    val service: ApplicationsService = new ApplicationsService(repository, clock, idmsConnector, emailConnector)
    Fixture(clock, repository, idmsConnector, emailConnector, service)
  }

}
