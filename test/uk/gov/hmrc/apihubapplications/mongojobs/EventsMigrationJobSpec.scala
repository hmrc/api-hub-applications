package uk.gov.hmrc.apihubapplications.mongojobs

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestStatus, Approved, Cancelled, Pending, Rejected}
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, TeamMember}
import uk.gov.hmrc.apihubapplications.models.team.{Team, TeamType}
import uk.gov.hmrc.apihubapplications.services.{AccessRequestsEventService, AccessRequestsService, ApplicationsEventService, ApplicationsService, TeamsEventService, TeamsService}

import java.time.LocalDateTime
import scala.concurrent.Future

class EventsMigrationJobSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  private val allApplications = Seq(
    buildApplication("1"),
    buildApplication("2"),
    buildApplication("3")
  )
  private val allAccessRequests = Seq(
    buildAccessRequest("1", "1", Approved),
    buildAccessRequest("1", "2", Rejected),
    buildAccessRequest("1", "3", Cancelled),
    buildAccessRequest("1", "4", Pending),
    buildAccessRequest("2", "5", Rejected),
    buildAccessRequest("3", "6", Approved)
  )
  private val allTeams = Seq(
    buildTeam("1"),
    buildTeam("2"),
    buildTeam("3")
  )

  "EventsMigrationJob" - {
    "must log Create Application events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          verify(fixture.applicationsService).findAll(eqTo(true))
          allApplications.foreach { application =>
            verify(fixture.applicationsEventService).register(
              eqTo(application),
              eqTo(application.createdBy.email),
              eqTo(application.created)
            )
          }
          succeed
      }
    }

    "must log Add Api to Application events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          verify(fixture.applicationsService).findAll(eqTo(true))
          allApplications.foreach { application =>
            application.apis.foreach { api =>
              verify(fixture.applicationsEventService).addApi(
                eqTo(application),
                eqTo(api),
                eqTo(EventsMigrationJob.MIGRATION_USER),
                eqTo(application.created)
              )
            }
          }
          succeed
      }
    }

    "must log Create Credential events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          verify(fixture.applicationsService).findAll(eqTo(true))
          allApplications.foreach { application =>
            application.credentials.foreach { credential =>
              verify(fixture.applicationsEventService).createCredential(
                eqTo(application),
                eqTo(credential),
                eqTo(EventsMigrationJob.MIGRATION_USER),
                eqTo(credential.created)
              )
            }
          }
          succeed
      }
    }

    "must log Create Access Request events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          verify(fixture.accessRequestsEventService).created(allAccessRequests.filter(_.applicationId == "appId1"))
          verify(fixture.accessRequestsEventService).created(allAccessRequests.filter(_.applicationId == "appId2"))
          verify(fixture.accessRequestsEventService).created(allAccessRequests.filter(_.applicationId == "appId3"))
          succeed
      }
    }

    "must log Approve Access Request events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          allAccessRequests.filter(_.status == Approved).foreach { accessRequest =>
            verify(fixture.accessRequestsEventService).approved(accessRequest)
          }
          succeed
      }
    }

    "must log Reject Access Request events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          allAccessRequests.filter(_.status == Rejected).foreach { accessRequest =>
            verify(fixture.accessRequestsEventService).rejected(accessRequest)
          }

          succeed
      }
    }

    "must log Cancel Access Request events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          allAccessRequests.filter(_.status == Cancelled).foreach { accessRequest =>
            verify(fixture.accessRequestsEventService).cancelled(accessRequest)
          }

          succeed
      }
    }

    "must log Create Team events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          allTeams.foreach { team =>
            verify(fixture.teamsEventService).create(
              team,
              EventsMigrationJob.MIGRATION_USER
            )
          }

          succeed
      }
    }

    "must log Add Egresses to Team events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          allTeams.foreach { team =>
            verify(fixture.teamsEventService).addEgresses(
              eqTo(team),
              eqTo(EventsMigrationJob.MIGRATION_USER),
              eqTo(team.egresses),
              eqTo(team.created)
            )
          }

          succeed
      }
    }

    "must log Add Member to Team events correctly" in {
      val fixture = buildFixture()

      fixture.job.run().map {
        result =>
          allTeams.foreach { team =>
            team.teamMembers.foreach { teamMember =>
              verify(fixture.teamsEventService).addMember(
                eqTo(team),
                eqTo(EventsMigrationJob.MIGRATION_USER),
                eqTo(teamMember.email),
                eqTo(team.created)
              )
            }
          }

          succeed
      }
    }
  }

  private case class Fixture(job: EventsMigrationJob,
                applicationsService: ApplicationsService,
                accessRequestsService: AccessRequestsService,
                teamsService: TeamsService,
                applicationsEventService: ApplicationsEventService,
                accessRequestsEventService: AccessRequestsEventService,
                teamsEventService: TeamsEventService)

  private def buildFixture() = {
    val applicationsService = mock[ApplicationsService]
    val accessRequestsService = mock[AccessRequestsService]
    val teamsService = mock[TeamsService]
    val applicationsEventService = mock[ApplicationsEventService]
    val accessRequestsEventService = mock[AccessRequestsEventService]
    val teamsEventService = mock[TeamsEventService]

    when(applicationsService.findAll(any())).thenReturn(Future.successful(allApplications))
    when(accessRequestsService.getAccessRequests(any(), any())).thenReturn(Future.successful(allAccessRequests))
    when(teamsService.findAll(any())).thenReturn(Future.successful(allTeams))

    when(applicationsEventService.register(any(), any(), any())).thenReturn(Future.successful(()))
    when(applicationsEventService.addApi(any(), any(), any(), any())).thenReturn(Future.successful(()))
    when(applicationsEventService.createCredential(any(), any(), any(), any())).thenReturn(Future.successful(()))
    when(applicationsEventService.removeApi(any(), any(), any(), any())).thenReturn(Future.successful(()))
    when(applicationsEventService.changeTeam(any(), any(), any(), any(), any())).thenReturn(Future.successful(()))

    when(accessRequestsEventService.created(any())).thenReturn(Future.successful(()))
    when(accessRequestsEventService.approved(any())).thenReturn(Future.successful(()))
    when(accessRequestsEventService.rejected(any())).thenReturn(Future.successful(()))
    when(accessRequestsEventService.cancelled(any())).thenReturn(Future.successful(()))

    when(teamsEventService.create(any(), any())).thenReturn(Future.successful(()))
    when(teamsEventService.addEgresses(any(), any(), any(), any())).thenReturn(Future.successful(()))
    when(teamsEventService.addMember(any(), any(), any(), any())).thenReturn(Future.successful(()))


    val job = new EventsMigrationJob(
      applicationsService,
      accessRequestsService,
      teamsService,
      applicationsEventService,
      accessRequestsEventService,
      teamsEventService
    )

    Fixture(job, applicationsService, accessRequestsService, teamsService, applicationsEventService, accessRequestsEventService, teamsEventService)
  }

  private def buildApplication(index: String) = {
    Application(
      id = Some(s"appId${index}"),
      name = s"app${index}",
      created = LocalDateTime.now(),
      createdBy = Creator(s"creator${index}@example.com"),
      lastUpdated = LocalDateTime.now(),
      teamId = Some(s"team${index}Id"),
      teamMembers = Seq.empty,
      apis = Seq(
        Api(s"app${index}apiId1", s"app${index}apiTitle1"),
        Api(s"app${index}apiId2", s"app${index}apiTitle2")
      ),
      deleted = None,
      teamName = None,
      credentials = Set(
        Credential(s"app${index}clientId1", LocalDateTime.now(), Some(s"app${index}clientSecret1"), Some(s"app${index}fragment1"), s"app${index}envId1"),
        Credential(s"app${index}clientId2", LocalDateTime.now(), Some(s"app${index}clientSecret2"), Some(s"app${index}fragment2"), s"app${index}envId2"),
      )
    )
  }

  private def buildAccessRequest(appIndex: String, index: String, status: AccessRequestStatus) = {
    AccessRequest(
      applicationId = s"appId${appIndex}",
      apiId = s"app${appIndex}apiId1",
      apiName = s"app${appIndex}apiTitle1",
      status = status,
      supportingInformation = s"supportingInfo${index}",
      requested = LocalDateTime.now(),
      requestedBy = s"accessRequester${index}@example.com",
      environmentId = s"envId${index}",
    )
  }

  private def buildTeam(index: String) = {
    Team(
      id = Some(s"team${index}Id"),
      name = s"team${index}",
      created = LocalDateTime.now(),
      teamMembers = Seq(
        TeamMember(email = s"team${index}member1@example.com"),
        TeamMember(email = s"team${index}member2@example.com"),
      ),
      teamType = TeamType.ConsumerTeam,
      egresses = Seq(
        s"egress1-${index}",
        s"egress2-${index}"
      )
    )
  }

}
