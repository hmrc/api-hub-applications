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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, times, verify, verifyNoMoreInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Approved}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception.*
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsLifecycleServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with EitherValues {

  import ApplicationsLifecycleServiceSpec._

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
      when(idmsConnector.createClient(eqTo(Primary), eqTo(Client(newApplication)))(any))
        .thenReturn(Future.successful(Right(primaryClientResponse)))

      val secondaryClientResponse = ClientResponse("secondary-client-id", "test-secret-5678")
      when(idmsConnector.createClient(eqTo(Secondary), eqTo(Client(newApplication)))(any))
        .thenReturn(Future.successful(Right(secondaryClientResponse)))

      val applicationWithCreds = application
        .setCredentials(Primary, Seq(primaryClientResponse.asNewHiddenCredential(clock)))
        .setCredentials(Secondary, Seq(secondaryClientResponse.asNewCredential(clock)))

      val saved = applicationWithCreds.copy(id = Some("test-id"))

      when(repository.insert(eqTo(applicationWithCreds)))
        .thenReturn(Future.successful(saved))

      when(searchService.findById(eqTo(saved.safeId), eqTo(false))(any))
        .thenReturn(Future.successful(Right(saved)))

      when(emailConnector.sendAddTeamMemberEmail(any)(any))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any)(any))
        .thenReturn(Future.successful(Right(())))

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

      when(idmsConnector.createClient(eqTo(Primary), eqTo(Client(newApplication)))(any))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

      val secondaryClientResponse = ClientResponse("secondary-client-id", "test-secret-5678")
      when(idmsConnector.createClient(eqTo(Secondary), eqTo(Client(newApplication)))(any))
        .thenReturn(Future.successful(Right(secondaryClientResponse)))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          verify(repository, times(0)).insert(any)
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
      when(idmsConnector.createClient(eqTo(Primary), eqTo(Client(newApplication)))(any))
        .thenReturn(Future.successful(Right(primaryClientResponse)))

      when(idmsConnector.createClient(eqTo(Secondary), eqTo(Client(newApplication)))(any))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          verify(repository, times(0)).insert(any)
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
      ).setCredentials(Secondary, Seq(clientResponse.asNewCredential(clock)))

      when(idmsConnector.createClient(any, any)(any))
        .thenReturn(Future.successful(Right(clientResponse)))

      val saved = expected.copy(id = Some("id"))

      when(repository.insert(any))
        .thenReturn(Future.successful(saved))

      when(searchService.findById(eqTo(saved.safeId), eqTo(false))(any))
        .thenReturn(Future.successful(Right(saved)))

      when(emailConnector.sendAddTeamMemberEmail(any)(any))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any)(any))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgumentCaptor.forClass(classOf[Application])
          verify(repository).insert(captor.capture)
          captor.getValue.teamMembers mustBe expected.teamMembers
          succeed
      }
    }

    "must email team members if no teamId is present" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      val clientResponse = ClientResponse("test-client-id", "test-secret-1234")

      when(idmsConnector.createClient(any, any)(any))
        .thenReturn(Future.successful(Right(clientResponse)))

      val saved = Application(newApplication, clock)
        .copy(id = Some("id"))
        .copy(teamId = None)
        .addCredential(Primary, clientResponse.asNewHiddenCredential(clock))
        .addCredential(Secondary, clientResponse.asNewCredential(clock))

      when(repository.insert(any))
        .thenReturn(Future.successful(saved))

      when(searchService.findById(eqTo(saved.safeId), eqTo(false))(any))
        .thenReturn(Future.successful(Right(saved)))

      when(emailConnector.sendAddTeamMemberEmail(any)(any))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any)(any))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          verify(emailConnector).sendAddTeamMemberEmail(eqTo(saved))(any)
          succeed
      }
    }

    "must email application creator if no teamId is present" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val newApplication = NewApplication("test-name", creator, Seq.empty)

      val clientResponse = ClientResponse("test-client-id", "test-secret-1234")

      when(idmsConnector.createClient(any, any)(any))
        .thenReturn(Future.successful(Right(clientResponse)))

      val saved = Application(newApplication, clock)
        .copy(id = Some("id"))
        .copy(teamId = None)
        .addCredential(Primary, clientResponse.asNewHiddenCredential(clock))
        .addCredential(Secondary, clientResponse.asNewCredential(clock))

      when(repository.insert(any))
        .thenReturn(Future.successful(saved))

      when(searchService.findById(eqTo(saved.safeId), eqTo(false))(any))
        .thenReturn(Future.successful(Right(saved)))

      when(emailConnector.sendAddTeamMemberEmail(any)(any))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any)(any))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          verify(emailConnector).sendApplicationCreatedEmailToCreator(eqTo(saved))(any)
          succeed
      }
    }

    "must not send any emails if teamId is present" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val newApplication = NewApplication("test-name", creator, Seq.empty)

      val clientResponse = ClientResponse("test-client-id", "test-secret-1234")

      when(idmsConnector.createClient(any, any)(any))
        .thenReturn(Future.successful(Right(clientResponse)))

      val saved = Application(newApplication, clock)
        .copy(id = Some("id"))
        .copy(teamId = Some("team-id"))
        .addCredential(Primary, clientResponse.asNewHiddenCredential(clock))
        .addCredential(Secondary, clientResponse.asNewCredential(clock))

      when(repository.insert(any)).thenReturn(Future.successful(saved))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          verify(searchService, never).findById(any)(any)
          verify(emailConnector, never).sendAddTeamMemberEmail(any)(any)
          verify(emailConnector, never).sendApplicationCreatedEmailToCreator(any)(any)
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

      val clientResponse = ClientResponse("test-client-id", "test-secret-1234")

      when(idmsConnector.createClient(any, any)(any))
        .thenReturn(Future.successful(Right(clientResponse)))

      val saved = Application(newApplication, clock)
        .copy(id = Some("id"))
        .addCredential(Primary, clientResponse.asNewHiddenCredential(clock))
        .addCredential(Secondary, clientResponse.asNewCredential(clock))

      when(repository.insert(any))
        .thenReturn(Future.successful(saved))

      when(searchService.findById(eqTo(saved.safeId), eqTo(false))(any))
        .thenReturn(Future.successful(Right(saved)))

      when(emailConnector.sendAddTeamMemberEmail(any)(any))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any)(any))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        result =>
          result.isRight mustBe true
      }
    }
  }

  "delete" - {
    "must soft delete the application and leave in the repository when it has access requests" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setCredentials(Primary, Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))
      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      val captor: ArgumentCaptor[Application] = ArgumentCaptor.forClass(classOf[Application])
      when(repository.update(captor.capture())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, eqTo(clientId))(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(id)), eqTo(None))).thenReturn(Future.successful(someAccessRequests))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).update(any)
          val captured = captor.getValue
          captured.deleted mustBe Some(Deleted(LocalDateTime.now(clock), currentUser))
          succeed
      }
    }

    "must hard delete the application when it does not have any access requests" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setCredentials(Primary, Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))
      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, eqTo(clientId))(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(id)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(repository.delete(any)).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).delete(eqTo(id))
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
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq(teamMember1, teamMember2))
        .setCredentials(Primary, Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, eqTo(clientId))(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(id)), eqTo(None))).thenReturn(Future.successful(someAccessRequests))

      service.delete(application.id.get, currentUser, FakeHipEnvironments)(HeaderCarrier()) map {
        _ =>
          verify(emailConnector).sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)
          verify(emailConnector).sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)
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
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq(teamMember1, teamMember2))
        .setCredentials(Primary, Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), any)(any)).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), any)(any)).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(idmsConnector.deleteClient(any, eqTo(clientId))(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(id)), eqTo(None))).thenReturn(Future.successful(someAccessRequests))

      service.delete(application.id.get, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
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
      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Left(notFoundException)))

      service.delete(application.id.get, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        result =>
          result mustBe Left(notFoundException)
          verifyNoMoreInteractions(emailConnector)
          succeed
      }
    }

    "must delete all clients from IDMS" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setCredentials(Primary, Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(id)), eqTo(None))).thenReturn(Future.successful(someAccessRequests))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(idmsConnector).deleteClient(any, any)(any)
          succeed
      }
    }

    "must cancel all access requests" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setCredentials(Primary, Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(id)), eqTo(None))).thenReturn(Future.successful(someAccessRequests))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(accessRequestsService).cancelAccessRequests(eqTo(id))
          succeed
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      when(searchService.findById(eqTo(id))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
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
        .setCredentials(Primary, Seq(Credential(clientId1, LocalDateTime.now(clock), None, None)))
        .setCredentials(Secondary, Seq(Credential(clientId2, LocalDateTime.now(clock), None, None)))

      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, eqTo(clientId1))(any))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))
      when(idmsConnector.deleteClient(any, eqTo(clientId2))(any))
        .thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
          verify(repository, times(0)).update(any)
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
        .setCredentials(Primary, Seq(Credential(clientId1, LocalDateTime.now(clock), None, None)))
        .setCredentials(Secondary, Seq(Credential(clientId2, LocalDateTime.now(clock), None, None)))

      when(searchService.findById(eqTo(id))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(eqTo(application), eqTo(currentUser))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, eqTo(clientId1))(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteClient(any, eqTo(clientId2))(any)).thenReturn(Future.successful(Right(())))
      val notUpdatedException = NotUpdatedException.forId(id)
      when(accessRequestsService.cancelAccessRequests(eqTo(id))).thenReturn(Future.successful(Left(notUpdatedException)))

      service.delete(id, currentUser, FakeHipEnvironments)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(notUpdatedException)
          verify(repository, times(0)).update(any)
          succeed
      }
    }
  }

  "addTeamMember" - {
    "must add the team member to the application and pass it to the repository" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).addTeamMember(TeamMember("test-existing-team-member-email"))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))

      val teamMember = TeamMember("test-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          val updated = application.addTeamMember(teamMember).updated(clock)
          verify(repository).update(eqTo(updated))
          result mustBe Right(())
      }
    }

    "must return TeamMemberExistsException if the team member already exists in the application" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).addTeamMember(TeamMember("test-existing-team-member-email"))

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      val teamMember = TeamMember("test-existing-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          result mustBe Left(TeamMemberExistsException.forId(applicationId))
      }
    }

    "must return any exception returned by the repository" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val expected = ApplicationNotFoundException.forId(applicationId)

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any))
        .thenReturn(Future.successful(Left(expected)))

      val teamMember = TeamMember("test-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }

    "must return ApplicationTeamMigratedException if the application has a global team" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        createdBy = Creator("test-email"),
        teamId = "test-team-id"
      )

      when(searchService.findById(eqTo(applicationId), eqTo(false))(any)).thenReturn(Future.successful(Right(application)))

      val teamMember = TeamMember("test-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationTeamMigratedException.forId(applicationId))
      }
    }
  }

  private case class Fixture(
    searchService: ApplicationsSearchService,
    accessRequestsService: AccessRequestsService,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    emailConnector: EmailConnector,
    service: ApplicationsLifecycleService
  )

  private def buildFixture: Fixture = {
    val searchService = mock[ApplicationsSearchService]
    val accessRequestsService = mock[AccessRequestsService]
    val repository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]
    val emailConnector = mock[EmailConnector]
    val service = new ApplicationsLifecycleServiceImpl(searchService, accessRequestsService, repository, idmsConnector, emailConnector, clock, FakeHipEnvironments)
    Fixture(searchService, accessRequestsService, repository, idmsConnector, emailConnector, service)
  }

}

object ApplicationsLifecycleServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  val currentUser = "me@test.com"

  val accessRequest: AccessRequest = AccessRequest(
    id = Some("test-id"),
    applicationId = "test-application-id",
    apiId = "test-api-id",
    apiName = "test-api-name",
    status = Approved,
    endpoints = Seq.empty,
    supportingInformation = "test-info",
    requested = LocalDateTime.now(clock),
    requestedBy = "test-requested-by",
    decision = None,
    cancelled = None
  )

  val someAccessRequests: Seq[AccessRequest] = Seq(accessRequest)

}
