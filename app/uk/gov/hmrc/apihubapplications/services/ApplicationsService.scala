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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequest
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms.Client
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.{ApplicationEnrichers, ScopeFixer}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstException
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsService @Inject()(
  repository: ApplicationsRepository,
  clock: Clock,
  idmsConnector: IdmsConnector,
  emailConnector: EmailConnector,
  accessRequestsService: AccessRequestsService,
  scopeFixer: ScopeFixer
)(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def addApi(applicationId: String, newApi: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    def doRepositoryUpdate(application: Application, newApi: AddApiRequest): Future[Either[ApplicationsException, Unit]] = {
      repository.update(
        application
          .removeApi(newApi.id)
          .addApi(Api(newApi.id, newApi.endpoints))
          .updated(clock)
      )
    }

    this.findById(applicationId, enrich = true).flatMap {
      case Right(application) =>
        val updated = application.removeApi(newApi.id)
        ApplicationEnrichers.process(
          updated,
          newApi.scopes.distinct.map(scope => ApplicationEnrichers.scopeAddingApplicationEnricher(Secondary, updated, idmsConnector, scope))
        ) flatMap {
          case Right(_) => doRepositoryUpdate(application, newApi) map {
            case Right(_) => Right(())
            case Left(e) => Left(e)
          }
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }

  }

  def removeApi(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    this.findById(applicationId, enrich = true).flatMap {
      case Right(application) if application.hasApi(apiId) => removeApi(application, apiId)
      case Right(_) => Future.successful(Left(raiseApiNotFoundException.forApplication(applicationId, apiId)))
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def removeApi(application: Application, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val updated = application
      .removeApi(apiId)
      .updated(clock)

    scopeFixer.fix(updated).flatMap {
      case Right(fixed) =>
        accessRequestsService.cancelAccessRequests(fixed.safeId, apiId).flatMap {
          case Right(_) => repository.update(fixed)
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  def registerApplication(newApplication: NewApplication)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
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

  def findAll(teamMemberEmail: Option[String], includeDeleted: Boolean): Future[Seq[Application]] = {
    repository.findAll(teamMemberEmail, includeDeleted)
  }

  def findById(id: String, enrich: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    repository.findById(id).flatMap {
      case Right(application) =>
        if (enrich) {
          ApplicationEnrichers.process(
            application,
            Seq(
              ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector),
              ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector),
              ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector)
            )
          )
        } else {
          Future.successful(Right(application))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def softDelete(application: Application, currentUser: String): Future[Either[ApplicationsException, Unit]] = {
    val softDeletedApplication = application.copy(deleted = Some(Deleted(LocalDateTime.now(clock), currentUser)))
    repository.update(softDeletedApplication)
  }

  def delete(applicationId: String, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
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

  def sendApplicationDeletedEmails(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val email1 = emailConnector.sendApplicationDeletedEmailToCurrentUser(application, currentUser)
    val email2 = emailConnector.sendApplicationDeletedEmailToTeam(application, currentUser)
    Future.sequence(Seq(email1, email2))
      .map {
        _ => Right(())
      }
  }

  private case class NewCredential(application: Application, credential: Credential, wasHidden: Boolean)

  def addCredential(applicationId: String, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]] = {
    findById(applicationId, enrich = true).map {
      case Right(application) => addCredentialValidation(application, environmentName)
      case Left(e) => Left(e)
    } flatMap {
      case Right(application) => updateOrAddCredential(application, environmentName)
      case Left(e) => Future.successful(Left(e))
    } flatMap {
      case Right(newCredential: NewCredential) => updateRepository(newCredential)
      case Left(e) => Future.successful(Left(e))
    } flatMap {
      case Right(newCredential: NewCredential) => copyScopes(newCredential, environmentName)
      case Left(e) => Future.successful(Left(e))
    } map {
      case Right(newCredential) => Right(newCredential.credential)
      case Left(e) => Left(e)
    }
  }

  private def addCredentialValidation(application: Application, environmentName: EnvironmentName): Either[ApplicationsException, Application] = {
    if (application.getCredentialsFor(environmentName).size < 5) {
      Right(application)
    }
    else {
      Left(ApplicationCredentialLimitException.forApplication(application, environmentName))
    }
  }

  private def updateOrAddCredential(application: Application, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, NewCredential]] = {
    (environmentName, application.getMasterCredentialFor(environmentName)) match {
      case (Primary, Some(credential)) if credential.isHidden =>
        idmsConnector.newSecret(environmentName, credential.clientId).map {
          case Right(secret) =>
            val newCredential = Credential(
              clientId = credential.clientId,
              created = LocalDateTime.now(clock),
              clientSecret = Some(secret.secret),
              secretFragment = Some(secret.secret.takeRight(4))
            )

            Right(NewCredential(application.replacePrimaryCredential(newCredential), newCredential, wasHidden = true))
          case Left(e) => Left(e)
        }
      case _ =>
        idmsConnector.createClient(environmentName, Client(application)).map {
          case Right(clientResponse) =>
            val newCredential = clientResponse.asNewCredential(clock)
            Right(NewCredential(application.addCredential(newCredential, environmentName), newCredential, wasHidden = false))
          case Left(e) => Left(e)
        }
    }
  }

  private def updateRepository(newCredential: NewCredential): Future[Either[ApplicationsException, NewCredential]] = {
    val updated = newCredential.application.copy(lastUpdated = LocalDateTime.now(clock))
    repository.update(updated).map {
      case Right(()) => Right(newCredential.copy(application = updated))
      case Left(e) => Left(e)
    }
  }

  private def copyScopes(newCredential: NewCredential, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, NewCredential]] = {
    if (!newCredential.wasHidden) {
      Future.sequence(
          newCredential.application
            .getScopesFor(environmentName)
            .map(
              scope =>
                idmsConnector.addClientScope(environmentName, newCredential.credential.clientId, scope.name)
            )
        )
        .map(useFirstException)
        .map {
          case Right(_) => Right(newCredential)
          case Left(e) => Left(e)
        }
    }
    else {
      Future.successful(Right(newCredential))
    }
  }

  def deleteCredential(applicationId: String, environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    findById(applicationId, enrich = false).flatMap {
      case Right(application) =>
        application.getCredentialsFor(environmentName).find(_.clientId == clientId) match {
          case Some(_) =>
            if (application.getCredentialsFor(environmentName).size > 1) {
              idmsConnector.deleteClient(environmentName, clientId).flatMap {
                case Right(_) => deleteCredential(application, environmentName, clientId)
                case Left(e: IdmsException) if e.issue == ClientNotFound => deleteCredential(application, environmentName, clientId)
                case Left(e) => Future.successful(Left(e))
              }
            }
            else {
              Future.successful(Left(raiseApplicationCredentialLimitException.forApplication(application, environmentName)))
            }
          case _ => Future.successful(Left(raiseCredentialNotFoundException.forClientId(clientId)))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def deleteCredential(application: Application, environmentName: EnvironmentName, clientId: String): Future[Either[ApplicationsException, Unit]] = {
    repository.update(
      application
        .removeCredential(clientId, environmentName)
        .copy(lastUpdated = LocalDateTime.now(clock))
    )
  }

  def addPrimaryAccess(accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    findById(accessRequest.applicationId, enrich = false).flatMap {
      case Right(application) =>
        Future.sequence(
            application.getPrimaryCredentials.flatMap(
              credential =>
                accessRequest.endpoints.flatMap(_.scopes).distinct.map(
                  scope =>
                    idmsConnector.addClientScope(Primary, credential.clientId, scope)
                )
            )
          )
          .map(useFirstException)
          .map {
            case Right(_) => Right(())
            case Left(e) => Left(e)
          }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def addTeamMember(
                     applicationId: String,
                     teamMember: TeamMember
                   )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    findById(applicationId, enrich = false).flatMap {
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
