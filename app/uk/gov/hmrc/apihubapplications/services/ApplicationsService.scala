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
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, IdmsException}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService.FetchCredentialsMapper
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsService @Inject()(
  repository: ApplicationsRepository,
  clock: Clock,
  idmsConnector: IdmsConnector
)(implicit ec: ExecutionContext) extends Logging{

  def registerApplication(newApplication: NewApplication)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Application]] = {
    Future.sequence(
      Seq(
        idmsConnector.createClient(Primary, Client(newApplication)),
        idmsConnector.createClient(Secondary, Client(newApplication))
      )
    ).flatMap {
      case Seq(Right(primaryClientResponse), Right(secondaryClientResponse)) =>
        repository.insert(
          Application(newApplication, clock)
            .setPrimaryCredentials(Seq(Credential(primaryClientResponse.clientId, None, None)))
            .setSecondaryCredentials(Seq(secondaryClientResponse.asCredential()))
            .assertTeamMember(newApplication.createdBy.email)
        ).map(Right(_))
      case _ => Future.successful(Left(IdmsException("Unable to create credentials")))
    }
  }

  def findAll(): Future[Seq[Application]] = {
    repository.findAll()
  }

  def filter(teamMemberEmail: String): Future[Seq[Application]] = {
    repository.filter(teamMemberEmail)
  }

  def findById(id: String)(implicit hc: HeaderCarrier): Future[Option[Either[IdmsException, Application]]] = {
    for {
      application <- repository.findById(id)
      credentials <- fetchSecondaryCredentials(application)
    } yield (application, credentials) match {
      case (Some(app), Right(creds)) => Some(Right(app.setSecondaryCredentials(creds)))
      case (_, Left(e)) => Some(Left(e))
      case _ => None
    }
  }

  def getApplicationsWithPendingScope(): Future[Seq[Application]] = findAll().map(_.filter(_.hasProdPendingScope))

  def addScopes(applicationId: String, newScopes: Seq[NewScope]): Future[Boolean] =
    repository.findById(applicationId).flatMap {
      case Some(application) =>
        val appWithNewScopes = newScopes.foldLeft[Application](application)((outerApp, newScope) => {
          newScope.environments.foldLeft[Application](outerApp)((innerApp, envName) =>
            innerApp.addScopes(envName, Seq(newScope.name))
          )
        }).copy(lastUpdated = LocalDateTime.now(clock))

        repository.update(appWithNewScopes)
      case None => Future.successful(false)
    }

  def setPendingProdScopeStatusToApproved(applicationId: String, scopeName:String): Future[Option[Boolean]] = {
    repository.findById(applicationId).flatMap {
      case Some(application)  =>
        if (application.getProdScopes.exists(scope => scope.name == scopeName && scope.status == Pending)){
          val updatedApp: Application = application.setProdScopes(
            application.environments.prod.scopes.map(scope =>
              if (scope.name == scopeName) scope.copy(status = Approved) else scope
            )).copy(lastUpdated = LocalDateTime.now(clock))
          repository.update(updatedApp).map(Some(_))
        }else{
          Future.successful(Some(false))
        }

      case None => Future.successful(None)
    }
  }

  private def fetchSecondaryCredentials(application: Option[Application])(implicit hc: HeaderCarrier): Future[Either[IdmsException, Seq[Credential]]] = {
    application
      .map {
        app =>
          Future.sequence(
            app.getSecondaryCredentials.map {
              credential =>
                idmsConnector.fetchClient(Secondary, credential.clientId)
            }
          ).map(FetchCredentialsMapper.mapToCredential)
      }
      .getOrElse(Future.successful(Right(Seq.empty)))
  }

}

object ApplicationsService {

  object FetchCredentialsMapper {

    def zero: Either[IdmsException, Seq[Credential]] = Right(Seq.empty)

    def op(a: Either[IdmsException, Seq[Credential]], b: Either[IdmsException, Credential]): Either[IdmsException, Seq[Credential]] = {
      (a, b) match {
        case (Left(e), _) => Left(e)
        case (_, Left(e)) => Left(e)
        case (Right(credentials), Right(credential)) => Right(credentials :+ credential)
      }
    }

    def mapToCredential(results: Seq[Either[IdmsException, ClientResponse]]): Either[IdmsException, Seq[Credential]] = {
      results.map {
        case Right(clientResponse) => Right(clientResponse.asCredentialWithSecret())
        case Left(e) => Left(e)
      }.foldLeft(zero)(op)
    }

  }

}
