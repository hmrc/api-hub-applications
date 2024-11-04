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
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequest
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationCredentialLimitException, ApplicationsException, ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.Client
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstException
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsCredentialsService {

  def addCredential(applicationId: String, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]]

  def deleteCredential(applicationId: String, environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def addPrimaryAccess(accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def fetchAllScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]]

}

@Singleton
class ApplicationsCredentialsServiceImpl @Inject()(
  searchService: ApplicationsSearchService,
  repository: ApplicationsRepository,
  idmsConnector: IdmsConnector,
  clock: Clock
)(implicit ec: ExecutionContext) extends ApplicationsCredentialsService with Logging with ExceptionRaising {

  import ApplicationsCredentialsServiceImpl.*

  override def addCredential(applicationId: String, environmentName: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]] = {
    searchService.findById(applicationId, enrich = true).map {
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

  override def deleteCredential(applicationId: String, environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = false).flatMap {
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

  override def addPrimaryAccess(accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(accessRequest.applicationId, enrich = false).flatMap {
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

  override def fetchAllScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]] = {
    searchService.findById(applicationId, enrich = false).flatMap {
      case Right(application) =>
        Future.sequence(
          Seq(Primary, Secondary).flatMap(
            environmentName =>
              application.getCredentialsFor(environmentName).map(
                credential =>
                  idmsConnector
                    .fetchClientScopes(environmentName, credential.clientId)
                    .map(_.map(
                      scopes =>
                        CredentialScopes(environmentName, credential.clientId, credential.created, scopes.map(_.clientScopeId))
                    ))
                  )
          )
        )
          .map(useFirstException)
          .map(_.map(_.sorted))
      case Left(e) => Future.successful(Left(e))
    }
  }

}

object ApplicationsCredentialsServiceImpl {

  private case class NewCredential(application: Application, credential: Credential, wasHidden: Boolean)

}
