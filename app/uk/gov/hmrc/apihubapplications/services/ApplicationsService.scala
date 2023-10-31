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
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.NewScope.implicits._
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms.Secret
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ApplicationEnrichers
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsService @Inject()(
                                     repository: ApplicationsRepository,
                                     clock: Clock,
                                     idmsConnector: IdmsConnector,
                                     emailConnector: EmailConnector
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
            deleteOperationResult <- repository.delete(application)
            _ <- emailConnector.sendApplicationDeletedEmailToCurrentUser(application, currentUser)
            _ <- emailConnector.sendApplicationDeletedEmailToTeam(application, currentUser)
          } yield deleteOperationResult
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
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
        idmsConnector.addClientScope(Primary, application.getPrimaryMasterCredential.clientId, scopeName)
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
        if (application.getPrimaryMasterCredential.isHidden) {
          idmsConnector.newSecret(Primary, application.getPrimaryMasterCredential.clientId).flatMap {
            case Right(secret) =>
              val updatedApplication = application
                .setPrimaryCredentials(Seq(application.getPrimaryMasterCredential.setSecretFragment(secret.secret)))
                .copy(lastUpdated = LocalDateTime.now(clock))

              repository.update(updatedApplication).map(
                _ => Right(secret)
              )
            case Left(e) => Future.successful(Left(e))
          }
        }
        else {
          Future.successful(Left(raiseApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials)))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def addCredential(applicationId: String, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]] = {
    findById(applicationId, true).flatMap {
      case Right(application) =>
        environmentName match {
          case Primary => addPrimaryCredential(application)
          case Secondary => addSecondaryCredential(application)
        }
      case Left(_) => Future.successful(Left(ApplicationNotFoundException.forId(applicationId)))
    }.flatMap {
      case Right(application) =>
        repository.update(application).map {
          case Right(()) => Right(application.getMasterCredentialFor(environmentName))
          case Left(e) => Left(e)
        }
      case Left(e) => Future.successful(Left(e))
    }.flatMap {
      case Right(masterCredential) => idmsConnector.fetchClient(environmentName, masterCredential.clientId).flatMap {
        case Right(clientResponse) => Future.successful(Right(Credential(masterCredential.clientId, masterCredential.created, Some(clientResponse.secret), None).setSecretFragment(clientResponse.secret)))
        case Left(e) => Future.successful(Left(e))
      }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def addPrimaryCredential(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    val credentials = application.getPrimaryCredentials

    if (credentials.isEmpty) {
      throw ApplicationDataIssueException.forApplication(application, NoCredentialsFound)
    } else {
      updateOrCreatePrimaryCredential(application)
    }
  }

  private def addSecondaryCredential(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    val credentials = application.getSecondaryCredentials

    if (credentials.isEmpty) {
      throw ApplicationDataIssueException.forApplication(application, NoCredentialsFound)
    }
    createNewCredentialAndCopyScopesFromPrevious(application, Secondary)

  }

  private def updateOrCreatePrimaryCredential(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    if (application.getPrimaryMasterCredential.isHidden) {
      updateExistingPrimaryMasterCredential(application)
    } else {
      createNewCredentialAndCopyScopesFromPrevious(application, Primary)
    }
  }

  private def updateExistingPrimaryMasterCredential(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    val masterCredential = application.getPrimaryMasterCredential
    idmsConnector.newSecret(Primary, masterCredential.clientId).map {
      case Right(secret) => Right(application.addPrimaryCredential(masterCredential.setSecretFragment(secret.secret).copy(created = LocalDateTime.now(clock), clientSecret = Some(secret.secret))))
      case Left(e) => Left(e)
    }
  }

  private def createNewCredential(application: Application, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    ApplicationEnrichers
      .process(application, Seq(ApplicationEnrichers.credentialCreatingApplicationEnricher(environmentName, application, idmsConnector, clock)))
  }

  private def createNewCredentialAndCopyScopesFromPrevious(application: Application, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    val currentScopes = application.getScopesFor(environmentName).filter(scope => scope.status == Approved)

    createNewCredential(application, environmentName).flatMap {
      case Right(application) => ApplicationEnrichers.process(application, Seq(ApplicationEnrichers.scopesSettingApplicationEnricher(environmentName, application, idmsConnector, currentScopes)))
      case Left(e) => Future.successful(Left(e))
    }
  }


}
