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
import org.apache.http.client.entity.EntityBuilder
import play.api.Logging
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestStatus, Approved, Cancelled, Rejected}
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.event.{Created, EntityType, Event, Registered, toAccessRequestApprovedEvent, toAccessRequestCancelledEvent, toAccessRequestCreatedEvent, toAccessRequestRejectedEvent, toApiAddedEvents, toCreatedEvent, toCredentialCreatedEvents, toEgressesAddedToTeamEvent, toTeamCreatedEvent, toTeamMemberAddedEvents, Application as ApplicationEntity}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.{ApplicationsRepository, EventsRepository}
import uk.gov.hmrc.apihubapplications.services.{AccessRequestsService, ApplicationsEventService, ApplicationsService, TeamsService}

import scala.concurrent.{ExecutionContext, Future}

class EventsMigrationJob @Inject()(
    eventsRepository: EventsRepository,
    applicationsService: ApplicationsService,
    accessRequestsService: AccessRequestsService,
    teamsService: TeamsService
)(implicit ec: ExecutionContext) extends MongoJob with Logging {

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

  private def runCreateApplicationEventMigration(allApplications: Seq[Application]): Future[Seq[Event]] = {
    createEventFor("Create Application", allApplications, application => {
      applicationsEventService.register(application, application.createdBy.email, application.created)
    })
  }

  private def runAddApiToApplicationEventMigration(allApplications: Seq[Application]): Future[Seq[Event]] = {
    createEventsFor("Add API to Application", allApplications, _.toApiAddedEvents)
  }

  private def runCreateCredentialEventMigration(allApplications: Seq[Application]): Future[Seq[Event]] = {
    createEventsFor("Create Credential for Application", allApplications, _.toCredentialCreatedEvents)
  }

  private def runCreateAccessRequestEventMigration(allApplications: Seq[Application],
                                                   accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Event]] = {
    createEventsFor("Create Access Request",
      allApplications,
      application => accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .map(application.toAccessRequestCreatedEvent(_))
    )
  }

  private def runApproveAccessRequestEventMigration(allApplications: Seq[Application],
                                                    accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Event]] = {
    createEventsFor("Approve Access Request",
      allApplications,
      application => accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .filter(_.status == Approved)
        .map(application.toAccessRequestApprovedEvent(_))
    )
  }

  private def runRejectAccessRequestEventMigration(allApplications: Seq[Application],
                                                   accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Event]] = {
    createEventsFor("Reject Access Request",
      allApplications,
      application => accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .filter(_.status == Rejected)
        .map(application.toAccessRequestRejectedEvent(_))
    )
  }

  private def runCancelAccessRequestEventMigration(allApplications: Seq[Application],
                                                   accessRequestsByApplicationId: Map[String,Seq[AccessRequest]]): Future[Seq[Event]] = {
    createEventsFor("Cancel Access Request",
      allApplications,
      application => accessRequestsByApplicationId.getOrElse(application.id.getOrElse(""), Seq.empty)
        .filter(_.status == Cancelled)
        .map(application.toAccessRequestCancelledEvent(_))
    )
  }

  private def runCreateTeamEventMigration(allTeams: Seq[Team]): Future[Seq[Event]] = {
    createEventFor("Create Team", allTeams, _.toTeamCreatedEvent)
  }

  private def runAddEgressesToTeamEventMigration(allTeams: Seq[Team]): Future[Seq[Event]] = {
    createEventsFor("Add Egresses to Team", allTeams, team => {
      team.egresses.size match {
        case 0 => Seq.empty
        case _ => Seq(team.toEgressesAddedToTeamEvent)
      }
    })
  }

  private def runAddTeamMemberToTeamEventMigration(allTeams: Seq[Team]): Future[Seq[Event]] = {
    createEventsFor("Add Team Member",
      allTeams,
      _.toTeamMemberAddedEvents
    )
  }

  private def createEventFor[T](description: String, entities: Seq[T], eventBuilder: T => Event) = {
    createEvents(description, entities, eventBuilder, Seq(_))
  }

  private def createEventsFor[T](description: String, entities: Seq[T], eventBuilder: T => Seq[Event]) = {
    createEvents(description, entities, eventBuilder, identity)
  }

  private def createEvents[T,E](description: String, entities: Seq[T], eventBuilder: T => E) = {
    logger.info(s"Starting event migration for $description...")
    val events = entities.flatMap(entity => eventFlattener(eventBuilder(entity)))
    eventsRepository.insertMany(events).map(insertedEvents => {
      logger.info(s"Inserted ${insertedEvents.size} events for $description")
      insertedEvents
    })
  }

}
