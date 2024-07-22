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
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.application.{Application, Deleted, NewApplication, Primary, Secondary, TeamMember}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ApplicationEnrichers
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsLifecycleService {

  def registerApplication(newApplication: NewApplication)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]]

  def delete(applicationId: String, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def addTeamMember(applicationId: String, teamMember: TeamMember)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

}

@Singleton
class ApplicationsLifecycleServiceImpl @Inject()(
  searchService: ApplicationsSearchService,
  accessRequestsService: AccessRequestsService,
  repository: ApplicationsRepository,
  idmsConnector: IdmsConnector,
  emailConnector: EmailConnector,
  clock: Clock
)(implicit ec: ExecutionContext) extends ApplicationsLifecycleService with Logging with ExceptionRaising {

  override def registerApplication(newApplication: NewApplication)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    val application = Application(newApplication, clock)
      .assertTeamMember(newApplication.createdBy.email)

    ApplicationEnrichers.process(
      application,
      Seq(
        ApplicationEnrichers.credentialCreatingApplicationEnricher(Primary, application, idmsConnector, clock),
        ApplicationEnrichers.credentialCreatingApplicationEnricher(Secondary, application, idmsConnector, clock)
      )
    ).flatMap {
      case Right(enriched) =>
        for {
          saved <- repository.insert(enriched)
          _ <- emailConnector.sendAddTeamMemberEmail(saved)
          _ <- emailConnector.sendApplicationCreatedEmailToCreator(saved)
        } yield Right(saved)
      case Left(e) => Future.successful(Left(e))
    }
  }

  override def delete(applicationId: String, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(applicationId).flatMap {
      case Right(application) =>
        idmsConnector.deleteAllClients(application) flatMap {
          case Right(_) =>
            accessRequestsService.cancelAccessRequests(applicationId) flatMap {
              case Right(_) => softDelete(application, currentUser) flatMap {
                case Right(_) => sendApplicationDeletedEmails(application, currentUser) flatMap {
                  _ => Future.successful(Right(()))
                }
                case Left(e) => Future.successful(Left(e))
              }
              case Left(e) => Future.successful(Left(e))
            }
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def softDelete(application: Application, currentUser: String): Future[Either[ApplicationsException, Unit]] = {
    val softDeletedApplication = application.copy(deleted = Some(Deleted(LocalDateTime.now(clock), currentUser)))
    repository.update(softDeletedApplication)
  }

  private def sendApplicationDeletedEmails(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val email1 = emailConnector.sendApplicationDeletedEmailToCurrentUser(application, currentUser)
    val email2 = emailConnector.sendApplicationDeletedEmailToTeam(application, currentUser)
    Future.sequence(Seq(email1, email2))
      .map {
        _ => Right(())
      }
  }

  override def addTeamMember(applicationId: String, teamMember: TeamMember)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = false).flatMap {
      case Right(application) if !application.hasTeamMember(teamMember) =>
        repository.update(
          application
            .addTeamMember(teamMember)
            .updated(clock)
        )
      case Right(application) =>
        Future.successful(Left(raiseTeamMemberExistsException.forApplication(application)))
      case Left(e) => Future.successful(Left(e))
    }
  }

}
