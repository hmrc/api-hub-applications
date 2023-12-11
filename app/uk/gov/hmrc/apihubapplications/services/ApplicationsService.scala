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
import uk.gov.hmrc.apihubapplications.models.application.NewScope.implicits._
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms.{Client, Secret}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ApplicationEnrichers
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
  accessRequestsService : AccessRequestsService
)(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def addApi(applicationId: String, newApi: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    def doRepositoryUpdate(application: Application, newApi: AddApiRequest): Future[Either[ApplicationsException, Unit]] = {
      repository.update(
        application
          .copy(lastUpdated = LocalDateTime.now(clock), apis = application.apis ++ Seq(Api(newApi.id, newApi.endpoints)))
      )
    }

    this.findById(applicationId, enrich = true).flatMap {
      case Right(application) =>
        val scopesRequired = newApi.scopes.toSet -- application.getSecondaryScopes.map(_.name).toSet

        doRepositoryUpdate(application, newApi).flatMap {
          case Right(_) => ApplicationEnrichers.process(
            application,
            scopesRequired.toSeq.map(scope => ApplicationEnrichers.scopeAddingApplicationEnricher(Secondary, application, idmsConnector, scope))
          ).flatMap {
            case Right(_) => Future.successful(Right(()))
            case Left(e) => Future.successful(Left(e))
          }
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
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

  def findAll(): Future[Seq[Application]] = {
    repository.findAll()
  }

  def filter(teamMemberEmail: String, enrich: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[Application]]] = {
    repository.filter(teamMemberEmail).flatMap {
      applications =>
        if (enrich) {
          ApplicationEnrichers.processAll(
            applications,
            ApplicationEnrichers.secondaryScopeApplicationEnricher _,
            idmsConnector,
            failOnError = true
          )
        }
        else {
          Future.successful(Right(applications))
        }
    }
  }

  def findById(id: String, enrich: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    repository.findById(id).flatMap {
      case Right(application) =>
        if (enrich) {
          ApplicationEnrichers.process(
            application,
            Seq(
              ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector),
              ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector, failOnError = false),
              ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector)
            )
          )
        } else {
          Future.successful(Right(application))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def getApplicationsWithPendingPrimaryScope: Future[Seq[Application]] = {
    findAll()
      .map(_.filter(_.hasPrimaryPendingScope))
  }

  def delete(applicationId: String, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(applicationId).flatMap {
      case Right(application) =>
        ApplicationEnrichers.process(
          application,
          application.getPrimaryCredentials.map(
            credential =>
              ApplicationEnrichers.credentialDeletingApplicationEnricher(Primary, credential.clientId, idmsConnector)
          ) ++
            application.getSecondaryCredentials.map(
              credential =>
                ApplicationEnrichers.credentialDeletingApplicationEnricher(Secondary, credential.clientId, idmsConnector)
            )
        ).flatMap {
          case Right(_) => for {
            _ <- idmsConnector.deleteAllClients(application)
            _ <- accessRequestsService.cancelAccessRequests(applicationId)
            deleteOperationResult <- repository.softDelete(application, currentUser)
            _ <- sendApplicationDeletedEmails(application, currentUser)
          } yield deleteOperationResult
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
      case _ => Right(())
    }
  }

  def addScopes(applicationId: String, newScopes: Seq[NewScope])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    def doRepositoryUpdate(application: Application, newScopes: Seq[NewScope]): Future[Either[ApplicationsException, Unit]] = {
      if (newScopes.hasPrimaryEnvironment) {
        repository.update(
          application
            .addScopes(
              Primary,
              newScopes
                .filter(_.hasPrimaryEnvironment)
                .map(_.name)
            )
            .copy(lastUpdated = LocalDateTime.now(clock))
        )
      }
      else {
        Future.successful(Right(()))
      }
    }

    def doIdmsUpdate(application: Application, newScopes: Seq[NewScope])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
      if (newScopes.hasSecondaryEnvironment) {
        ApplicationEnrichers.process(
          application,
          newScopes
            .filter(_.hasSecondaryEnvironment)
            .map(
              newScope =>
                ApplicationEnrichers.scopeAddingApplicationEnricher(Secondary, application, idmsConnector, newScope.name)
            )
        ).map(_.map(_ => ()))
      }
      else {
        Future.successful(Right(()))
      }
    }

    repository.findById(applicationId).flatMap {
      case Right(application) =>
        val repositoryUpdate = doRepositoryUpdate(application, newScopes)
        val idmsUpdate = doIdmsUpdate(application, newScopes)

        for {
          repositoryUpdated <- repositoryUpdate
          idmsUpdated <- idmsUpdate
        } yield (repositoryUpdated, idmsUpdated) match {
          case (Right(_), Right(_)) => Right(())
          case (_, Left(e)) => Left(e)
          case (Left(e), _) => Left(e)
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def approvePrimaryScope(applicationId: String, scopeName: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    def removePrimaryScope(application: Application, scopeId: String): Future[Either[ApplicationsException, Unit]] = {
      val prunedScopes = application.getPrimaryScopes.filterNot(scope => scope.name == scopeId)
      val prunedApplication = application.setPrimaryScopes(prunedScopes)
      repository.update(prunedApplication)
    }

    def idmsApprovePrimaryScope(application: Application, scopeName: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
      if (application.getPrimaryScopes.exists(scope => scope.name == scopeName && scope.status == Pending)) {
        application.getPrimaryMasterCredential match {
          case Some(credential) =>
            idmsConnector.addClientScope(Primary, credential.clientId, scopeName)
          case None =>
            Future.successful(Left(raiseApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials)))
        }
      } else {
        Future.successful(Left(raiseApplicationDataIssueException.forApplication(application, InvalidPrimaryScope)))
      }
    }

    repository.findById(applicationId).flatMap {
      case Right(application) => idmsApprovePrimaryScope(application, scopeName).flatMap {
        case Right(_) => removePrimaryScope(application, scopeName)
        case Left(e) => Future.successful(Left(e))
      }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def createPrimarySecret(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Secret]] = {
    repository.findById(applicationId).flatMap {
      case Right(application) =>
        application.getPrimaryMasterCredential match {
          case Some(credential) if credential.isHidden =>
            idmsConnector.newSecret(Primary, credential.clientId).flatMap {
              case Right(secret) =>
                val updatedApplication = application
                  .setPrimaryCredentials(Seq(credential.setSecretFragment(secret.secret)))
                  .copy(lastUpdated = LocalDateTime.now(clock))

                repository.update(updatedApplication).map(
                  _ => Right(secret)
                )
              case Left(e) => Future.successful(Left(e))
            }
          case _ =>
            Future.successful(Left(raiseApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials)))
        }
      case Left(e) => Future.successful(Left(e))
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
          .filter(_.status == Approved)
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

}
