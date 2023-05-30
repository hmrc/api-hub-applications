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
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising, InvalidPrimaryCredentials, InvalidPrimaryScope, InvalidSecondaryCredentials}
import uk.gov.hmrc.apihubapplications.models.idms.Secret
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ApplicationEnrichers
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsService @Inject()(
                                     repository: ApplicationsRepository,
                                     clock: Clock,
                                     idmsConnector: IdmsConnector
                                   )(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def registerApplication(newApplication: NewApplication)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    val application = Application(newApplication, clock)
      .assertTeamMember(newApplication.createdBy.email)

    ApplicationEnrichers.process(
      application,
      Seq(
        ApplicationEnrichers.credentialCreatingApplicationEnricher(Primary, application, idmsConnector),
        ApplicationEnrichers.credentialCreatingApplicationEnricher(Secondary, application, idmsConnector)
      )
    ).flatMap {
      case Right(enriched) => repository.insert(enriched).map(Right(_))
      case Left(e) => Future.successful(Left(e))
    }
  }

  def findAll(): Future[Seq[Application]] = {
    repository.findAll()
  }

  def filter(teamMemberEmail: String): Future[Seq[Application]] = {
    repository.filter(teamMemberEmail)
  }

  def findById(id: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    repository.findById(id).flatMap {
      case Right(application) =>
        ApplicationEnrichers.process(
          application,
          Seq(
            ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector),
            ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector),
            ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector)
          )
        )
      case Left(e) => Future.successful(Left(e))
    }
  }

  def getApplicationsWithPendingPrimaryScope: Future[Seq[Application]] = findAll().map(_.filter(_.hasPrimaryPendingScope))

  def delete(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
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
          case Right(_) => repository.delete(application)
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def addScope(applicationId: String, newScope: NewScope)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    def doRepositoryUpdate(application: Application, newScope: NewScope): Future[Either[ApplicationsException, Unit]] = {
      if (newScope.environments.exists(e => e.equals(Primary))) {
        val applicationWithNewPrimaryScope = application.addScopes(Primary, Seq(newScope.name)).copy(lastUpdated = LocalDateTime.now(clock))
        repository.update(applicationWithNewPrimaryScope)
      } else {
        Future.successful(Right(()))
      }
    }

    def doIdmsUpdate(application: Application, newScope: NewScope)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
      if (newScope.environments.exists(e => e.equals(Secondary))) {
        qaTechDeliveryValidSecondaryCredential(application) match {
          case Some(credential) => idmsConnector.addClientScope(Secondary, credential.clientId, newScope.name)
          case _ =>
            Future.successful(Left(raiseApplicationDataIssueException.forApplication(application, InvalidSecondaryCredentials)))
        }
      } else {
        Future.successful(Right(()))
      }
    }

    repository.findById(applicationId).flatMap {
      case Right(application) =>
        val repositoryUpdate = doRepositoryUpdate(application, newScope)
        val idmsUpdate = doIdmsUpdate(application, newScope)

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
        val maybeIdmsResult = qaTechDeliveryValidPrimaryCredential(application).map(credential => idmsConnector.addClientScope(Primary, credential.clientId, scopeName))
        maybeIdmsResult.getOrElse(Future.successful(Left(raiseApplicationDataIssueException.forApplication(application, InvalidPrimaryCredentials))))
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
        qaTechDeliveryValidPrimaryCredentialNoSecret(application) match {
          case Some(credential) =>
            idmsConnector.newSecret(Primary, credential.clientId).flatMap {
              case Right(secret) =>
                val updatedApplication = application
                  .setPrimaryCredentials(Seq(secret.toCredentialWithFragment(credential.clientId)))
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

  private def qaTechDeliveryValidPrimaryCredential(application: Application): Option[Credential] = {
    if (application.getPrimaryCredentials.length != 1) {
      None
    }
    else {
      application.getPrimaryCredentials.headOption.filter(_.clientId != null)
    }
  }

  private def qaTechDeliveryValidPrimaryCredentialNoSecret(application: Application): Option[Credential] = {
    qaTechDeliveryValidPrimaryCredential(application)
      .filter(_.secretFragment.isEmpty)
  }

  private def qaTechDeliveryValidSecondaryCredential(application: Application): Option[Credential] = {
    if (application.getSecondaryCredentials.length != 1) {
      None
    }
    else {
      application.getSecondaryCredentials.headOption.filter(_.clientId != null)
    }
  }
}
