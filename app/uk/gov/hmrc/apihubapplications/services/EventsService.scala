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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.AppConfig
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.event.{EntityType, Event}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, EgressNotFoundException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.requests.TeamMemberRequest
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*
import uk.gov.hmrc.apihubapplications.models.team.{AddEgressesRequest, NewTeam, RenameTeamRequest, Team}
import uk.gov.hmrc.apihubapplications.repositories.{EventsRepository, TeamsRepository}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class EventsService @Inject()(
  repository: EventsRepository,
  appConfig: AppConfig
)(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def log(event: Event): Future[Unit] = {
    if (appConfig.eventsEnabled) {
      repository
        .insert(event)
        .map(_ => ())
        .recoverWith {
          case NonFatal(ex) =>
            logger.warn(s"Failed to log an event: entityType=${event.entityType}, entityId=${event.entityId}, eventType=${event.eventType}", ex)
            Future.successful(())
        }
    } else {
      Future.successful(())
    }
  }

  def findById(id: String): Future[Option[Event]] = {
    repository.findById(id) flatMap {
      case Right(event) => Future.successful(Some(event))
      case _ => Future.successful(None)
    }
  }

  def findByEntity(entityType: EntityType, entityId: String): Future[Seq[Event]] = {
    repository.findByEntity(entityType, entityId)
  }

  def findByUser(user: String): Future[Seq[Event]] = {
    repository.findByUser(user)
  }

}
