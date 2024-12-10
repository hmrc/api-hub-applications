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

import cats.data.EitherT
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationCredentialLimitException, ApplicationsException, ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.Client
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{useFirstApplicationsException, useFirstException}
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsCredentialsService {

  def getCredentials(applicationId: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[Credential]]]

  def addCredential(applicationId: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]]

  def deleteCredential(applicationId: String, hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def fetchAllScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]]

}

@Singleton
class ApplicationsCredentialsServiceImpl @Inject()(
  searchService: ApplicationsSearchService,
  repository: ApplicationsRepository,
  idmsConnector: IdmsConnector,
  clock: Clock,
  accessRequestsService: AccessRequestsService,
  scopeFixer: ScopeFixer,
  hipEnvironments: HipEnvironments
)(implicit ec: ExecutionContext) extends ApplicationsCredentialsService with Logging with ExceptionRaising {

  import ApplicationsCredentialsServiceImpl.*

  override def getCredentials(applicationId: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[Credential]]] = {
    searchService.findById(applicationId, enrich = false).flatMap {
      case Right(application) =>
        if (!hipEnvironment.isProductionLike) {
          Future.sequence(
            application
              .getCredentials(hipEnvironment)
              .map(
                credential =>
                  idmsConnector
                    .fetchClient(hipEnvironment.environmentName, credential.clientId)
                    .map {
                      case Right(clientResponse) =>
                        Right(credential.setSecret(clientResponse.secret))
                      case Left(e) => Left(e)
                    }
              )
          ).map(useFirstApplicationsException)
        }
        else {
          Future.successful(Right(application.getCredentials(hipEnvironment)))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  override def addCredential(applicationId: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]] = {
    searchService.findById(applicationId, enrich = true).map {
      case Right(application) => addCredentialValidation(application, hipEnvironment)
      case Left(e) => Left(e)
    } flatMap {
      case Right(application) => updateOrAddCredential(application, hipEnvironment)
      case Left(e) => Future.successful(Left(e))
    } flatMap {
      case Right(newCredential: NewCredential) => updateRepository(newCredential)
      case Left(e) => Future.successful(Left(e))
    } flatMap {
      case Right(newCredential: NewCredential) => copyScopes(newCredential)
      case Left(e) => Future.successful(Left(e))
    } map {
      case Right(newCredential) => Right(newCredential.credential)
      case Left(e) => Left(e)
    }
  }

  private def addCredentialValidation(application: Application, hipEnvironment: HipEnvironment): Either[ApplicationsException, Application] = {
    if (application.getCredentials(hipEnvironment).size < 5) {
      Right(application)
    }
    else {
      Left(ApplicationCredentialLimitException.forApplication(application, hipEnvironment))
    }
  }

  private def updateOrAddCredential(application: Application, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, NewCredential]] = {
    application.getMasterCredential(hipEnvironment) match {
      case Some(credential) if credential.isHidden && hipEnvironment.isProductionLike =>
        idmsConnector.newSecret(hipEnvironment.environmentName, credential.clientId).map {
          case Right(secret) =>
            val newCredential = Credential(
              clientId = credential.clientId,
              created = LocalDateTime.now(clock),
              clientSecret = Some(secret.secret),
              secretFragment = Some(secret.secret.takeRight(4)),
              environmentId = hipEnvironment.id
            )

            Right(NewCredential(application.replaceCredential(hipEnvironment, newCredential), newCredential, wasHidden = true))
          case Left(e) => Left(e)
        }
      case _ =>
        idmsConnector.createClient(hipEnvironment.environmentName, Client(application)).map {
          case Right(clientResponse) =>
            val newCredential = clientResponse.asNewCredential(clock, hipEnvironment)
            Right(NewCredential(application.addCredential(hipEnvironment, newCredential), newCredential, wasHidden = false))
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

  private def copyScopes(newCredential: NewCredential)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, NewCredential]] = {
    (for {
      accessRequests <- EitherT.right(accessRequestsService.getAccessRequests(Some(newCredential.application.safeId), None))
      fixed <- EitherT(scopeFixer.fix(newCredential.application, accessRequests))
    } yield newCredential).value
  }

  override def deleteCredential(applicationId: String, hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = false).flatMap {
      case Right(application) =>
        application.getCredentials(hipEnvironment).find(_.clientId == clientId) match {
          case Some(_) =>
            if (application.getCredentials(hipEnvironment).size > 1) {
              idmsConnector.deleteClient(hipEnvironment.environmentName, clientId).flatMap {
                case Right(_) => deleteCredential(application, hipEnvironment, clientId)
                case Left(e: IdmsException) if e.issue == ClientNotFound => deleteCredential(application, hipEnvironment, clientId)
                case Left(e) => Future.successful(Left(e))
              }
            }
            else {
              Future.successful(Left(raiseApplicationCredentialLimitException.forApplication(application, hipEnvironment)))
            }
          case _ => Future.successful(Left(raiseCredentialNotFoundException.forClientId(clientId)))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def deleteCredential(application: Application, hipEnvironment: HipEnvironment, clientId: String): Future[Either[ApplicationsException, Unit]] = {
    repository.update(
      application
        .removeCredential(hipEnvironment, clientId)
        .copy(lastUpdated = LocalDateTime.now(clock))
    )
  }

  override def fetchAllScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]] = {
    searchService.findById(applicationId, enrich = false).flatMap {
      case Right(application) =>
        Future.sequence(
          hipEnvironments.environments.flatMap(
            hipEnvironment =>
              application.getCredentials(hipEnvironment).map(
                credential =>
                  idmsConnector
                    .fetchClientScopes(hipEnvironment.environmentName, credential.clientId)
                    .map(_.map(
                      scopes =>
                        CredentialScopes(hipEnvironment.id, credential.clientId, credential.created, scopes.map(_.clientScopeId))
                    ))
              )
          )
        ).map(useFirstException)
      case Left(e) => Future.successful(Left(e))
    }
  }

}

object ApplicationsCredentialsServiceImpl {

  private case class NewCredential(application: Application, credential: Credential, wasHidden: Boolean)

}
