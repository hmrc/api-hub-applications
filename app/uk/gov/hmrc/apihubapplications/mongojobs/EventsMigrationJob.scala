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

import com.google.inject.Inject
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestCancelRequest, AccessRequestDecisionRequest, AccessRequestRequest, Approved, Cancelled, Rejected}
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.services.{AccessRequestsEventService, AccessRequestsService, ApplicationsEventService, ApplicationsService, TeamsEventService, TeamsService}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class EventsMigrationJob @Inject()(
    applicationsService: ApplicationsService,
    accessRequestsService: AccessRequestsService,
    teamsService: TeamsService,
    applicationsEventService: ApplicationsEventService,
    accessRequestsEventService: AccessRequestsEventService,
    teamsEventService: TeamsEventService
)(implicit ec: ExecutionContext) extends MongoJob with Logging {

  private val MIGRATION_USER = "<migration>"
  private val UNKNOWN = "<unknown>"
  private val UNUSED_STRING = ""
  private val UNUSED_SEQ = Seq.empty

  override def run(): Future[Unit] = {
    logger.info("Event migration job is running")

    for {
      allApplications <- applicationsService.findAll(includeDeleted = true)
      accessRequestsByApplication <- accessRequestsService.getAccessRequests(None, None).map(_.groupBy(_.applicationId))
      allTeams <- teamsService.findAll(None)
      
      _ <- runCreateApplicationEventMigration(allApplications)
      _ <- runAddApiToApplicationEventMigration(allApplications)
      _ <- runCreateCredentialEventMigration(allApplications)
      _ <- runCreateAccessRequestEventMigration(allApplications, accessRequestsByApplication)
      _ <- runApproveAccessRequestEventMigration(allApplications, accessRequestsByApplication)
      _ <- runRejectAccessRequestEventMigration(allApplications, accessRequestsByApplication)
      _ <- runCancelAccessRequestEventMigration(allApplications, accessRequestsByApplication)
      _ <- runCreateTeamEventMigration(allTeams)
      _ <- runAddEgressesToTeamEventMigration(allTeams)
      _ <- runAddTeamMemberToTeamEventMigration(allTeams)
    } yield {
      logger.info("Event migration job is completed")
      Future(())
    }
  }

  private def runCreateApplicationEventMigration(allApplications: Seq[Application]): Future[Seq[Unit]] = {
    logEventForEach("Create Application", allApplications, application =>
      applicationsEventService.register(application, application.createdBy.email, application.created)
    )
  }

  private def runAddApiToApplicationEventMigration(allApplications: Seq[Application]): Future[Seq[Unit]] = {
    logEventsForEach("Add API to Application", allApplications, application =>
      application.apis.map(api => applicationsEventService.addApi(application, api, MIGRATION_USER, application.created))
    )
  }

  private def runCreateCredentialEventMigration(allApplications: Seq[Application]): Future[Seq[Unit]] = {
    logEventsForEach("Create Credential for Application", allApplications, application =>
      application.credentials.map(credential => applicationsEventService.createCredential(application, credential, MIGRATION_USER, credential.created)).toSeq
    )
  }

  private def runCreateAccessRequestEventMigration(allApplications: Seq[Application],
                                                   accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Unit]] = {
    logEventForEach("Create Access Request", allApplications, application => {
      val accessRequestsForApplication = accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
      val accessRequestRequest = AccessRequestRequest(
        application.id.getOrElse(UNKNOWN), UNUSED_STRING, MIGRATION_USER, UNUSED_SEQ, UNUSED_STRING
      )
      accessRequestsEventService.create(accessRequestRequest, accessRequestsForApplication)
    })
  }

  private def runApproveAccessRequestEventMigration(allApplications: Seq[Application],
                                                    accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Unit]] = {
    logEventsForEach("Approve Access Request", allApplications, application => {
      accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .filter(_.status == Approved)
        .map(approvedAccessRequest => {
          val accessRequestDecisionRequest = AccessRequestDecisionRequest( //TODO remove after Mikes change
            approvedAccessRequest.decision.get.decidedBy,
            None
          )
          accessRequestsEventService.approve(accessRequestDecisionRequest, approvedAccessRequest, LocalDateTime.now()) //TODO remove LDT after mikes change
        })
    })
  }

  private def runRejectAccessRequestEventMigration(allApplications: Seq[Application],
                                                   accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Unit]] = {
    logEventsForEach("Reject Access Request", allApplications, application => {
      accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .filter(_.status == Rejected)
        .map(rejectedAccessRequest => {
          val accessRequestDecisionRequest = AccessRequestDecisionRequest( //TODO remove after Mikes change
            rejectedAccessRequest.decision.get.decidedBy,
            None
          )
          accessRequestsEventService.reject(accessRequestDecisionRequest, rejectedAccessRequest, LocalDateTime.now()) //TODO remove LDT after mikes change
        })
    })
  }

  private def runCancelAccessRequestEventMigration(allApplications: Seq[Application],
                                                   accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Unit]] = {
    logEventsForEach("Cancel Access Request", allApplications, application => {
      accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .filter(_.status == Cancelled)
        .map(cancelledAccessRequest => {
          val accessRequestCancelRequest = AccessRequestCancelRequest( //TODO remove after Mikes change
            cancelledAccessRequest.cancelled.get.cancelledBy
          )
          accessRequestsEventService.cancel(accessRequestCancelRequest, cancelledAccessRequest, LocalDateTime.now()) //TODO remove LDT after mikes change
        })
    })
  }

  private def runCreateTeamEventMigration(allTeams: Seq[Team]): Future[Seq[Unit]] = {
    logEventForEach("Create Team", allTeams, team =>
      teamsEventService.create(team, MIGRATION_USER)
    )
  }

  private def runAddEgressesToTeamEventMigration(allTeams: Seq[Team]): Future[Seq[Unit]] = {
    logEventForEach("Add Egresses to Team", allTeams, team =>
      teamsEventService.addEgresses(team, MIGRATION_USER, team.egresses, team.created)
    )
  }

  private def runAddTeamMemberToTeamEventMigration(allTeams: Seq[Team]): Future[Seq[Unit]] = {
    logEventsForEach("Add Member to Team", allTeams, team =>
      team.teamMembers.map(teamMember => teamsEventService.addMember(team, MIGRATION_USER, teamMember.email, team.created))
    )
  }

  private def logEventForEach[T](description: String, entities: Seq[T], eventLogger: T => Future[Unit]): Future[Seq[Unit]] = {
    logEvents(description, entities, eventLogger, Seq(_))
  }

  private def logEventsForEach[T](description: String, entities: Seq[T], eventLogger: T => Seq[Future[Unit]]): Future[Seq[Unit]] = {
    logEvents(description, entities, eventLogger, identity)
  }

  private def logEvents[T,E](description: String, entities: Seq[T], eventLogger: T => E, flattener: E => Seq[Future[Unit]]): Future[Seq[Unit]] = {
    logger.info(s"Starting event migration for $description...")
    Future.sequence(entities.flatMap(entity => flattener(eventLogger(entity)))).map(insertionResults => {
      // Can only give a lower bound for number of events inserted because some services insert multiple events for a single call
      logger.info(s"Inserted at least ${insertionResults.size} events for $description")
      insertionResults
    })
  }

}
