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

package uk.gov.hmrc.apihubapplications.services.helpers

import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstException
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationEnricher {

  def enrich(application: Application): Application

}

object ApplicationEnrichers {

  private type EnricherProvider = (Application, IdmsConnector) => Future[Either[IdmsException, ApplicationEnricher]]

  def process(
               application: Application,
               enrichers: Seq[Future[Either[IdmsException, ApplicationEnricher]]]
             )(implicit ec: ExecutionContext): Future[Either[IdmsException, Application]] = {
    Future.sequence(enrichers)
      .map(useFirstException)
      .map {
        case Right(enrichers) =>
          Right(enrichers.foldLeft(application)((newApplication, enricher) => enricher.enrich(newApplication)))
        case Left(e) => Left(e)
      }
  }

  def processAll(
                  applications: Seq[Application],
                  enricherProvider: EnricherProvider,
                  idmsConnector: IdmsConnector
                )(implicit ec: ExecutionContext): Future[Either[IdmsException, Seq[Application]]] = {
    Future.sequence(
      applications.map(
        application =>
          enricherProvider(application, idmsConnector).map {
            case Right(enricher) => Right(enricher.enrich(application))
            case Left(error: IdmsException) => Left(error)
          }
      )
    ).map(useFirstException)
  }

  private val noOpApplicationEnricher = new ApplicationEnricher {
    override def enrich(application: Application): Application = {
      application
    }
  }

  def secondaryCredentialApplicationEnricher(
                                              original: Application,
                                              idmsConnector: IdmsConnector
                                            )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    type IssueOrClientResponse = Either[String, ClientResponse]

    def toIssuesOrClientResponses(results: Seq[Either[IdmsException, ClientResponse]]): Seq[Either[IdmsException, IssueOrClientResponse]] = {
      results.map {
        case Right(clientResponse) => Right(Right(clientResponse))
        case Left(e: IdmsException) => Right(Left(Issues.secondaryCredentialNotFound(e)))
      }
    }

    def buildEnricher(issuesOrResponses: Seq[IssueOrClientResponse]): ApplicationEnricher = {
      (application: Application) => {
        issuesOrResponses.foldLeft(application)(
          (app, issueOrResponse) =>
            issueOrResponse match {
              case Right(clientResponse) => app.updateCredential(Secondary, clientResponse.clientId, clientResponse.secret)
              case Left(issue) => app.addIssue(issue)
            }
        )
      }
    }

    Future.sequence(
        original.getCredentials(Secondary).map {
          credential =>
            idmsConnector.fetchClient(Secondary, credential.clientId)
        }
      )
      .map(toIssuesOrClientResponses)
      .map(useFirstException)
      .map {
        case Right(issuesOrResponses) => Right(buildEnricher(issuesOrResponses))
        case Left(e) => Left(e)
      }
  }

  def secondaryScopeApplicationEnricher(
                                         original: Application,
                                         idmsConnector: IdmsConnector
                                       )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    def buildEnricher(clientScopes: Seq[ClientScope]): ApplicationEnricher = {
      (application: Application) => {
        application.setScopes(
          Secondary, 
          clientScopes.map(clientScope => Scope(clientScope.clientScopeId))
        )
      }
    }

    def buildIssuesEnricher(idmsException: IdmsException): ApplicationEnricher = {
      (application: Application) => {
        application.addIssue(Issues.secondaryScopesNotFound(idmsException))
      }
    }

    original.getMasterCredential(Secondary) match {
      case Some(credential) =>
        idmsConnector
          .fetchClientScopes(Secondary, credential.clientId)
          .map {
            case Right(clientScopes) => Right(buildEnricher(clientScopes))
            case Left(e: IdmsException) => Right(buildIssuesEnricher(e))
          }
      case None => Future.successful(Right(noOpApplicationEnricher))
    }
  }

  def primaryScopeApplicationEnricher(
                                       original: Application,
                                       idmsConnector: IdmsConnector
                                     )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    def buildEnricher(clientScopes: Seq[ClientScope]): ApplicationEnricher = {
      (application: Application) => {
        application.setScopes(
          Primary, 
          clientScopes.map(clientScope => Scope(clientScope.clientScopeId))
        )
      }
    }

    def buildIssuesEnricher(idmsException: IdmsException): ApplicationEnricher = {
      (application: Application) => {
        application.addIssue(Issues.primaryScopesNotFound(idmsException))
      }
    }

    original.getMasterCredential(Primary) match {
      case Some(credential) =>
        idmsConnector
          .fetchClientScopes(Primary, credential.clientId)
          .map {
            case Right(clientScopes) => Right(buildEnricher(clientScopes))
            case Left(e: IdmsException) => Right(buildIssuesEnricher(e))
          }
      case None => Future.successful(Right(noOpApplicationEnricher))
    }
  }

  def credentialCreatingApplicationEnricher(
                                             hipEnvironment: HipEnvironment,
                                             original: Application,
                                             idmsConnector: IdmsConnector,
                                             clock: Clock,
                                             hiddenPrimary: Boolean = true
                                           )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
      idmsConnector.createClient(hipEnvironment.environmentName, Client(original)).map {
        case Right(clientResponse) =>
          Right(
            (application: Application) => {
              if hipEnvironment.isProductionLike && hiddenPrimary then
                  application.addCredential(hipEnvironment.environmentName, clientResponse.asNewHiddenCredential(clock, hipEnvironment))
              else application.addCredential(hipEnvironment.environmentName, clientResponse.asNewCredential(clock, hipEnvironment))
            }
          )
        case Left(e) => Left(e)
      }
  }

  def scopeAddingApplicationEnricher(
                                      environmentName: EnvironmentName,
                                      original: Application,
                                      idmsConnector: IdmsConnector,
                                      scopeName: String
                                    )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.sequence(
        original
          .getCredentials(environmentName)
          .map(
            credential =>
              idmsConnector.addClientScope(environmentName, credential.clientId, scopeName)
          )
      )
      .map(useFirstException)
      .map {
        case Right(_) => Right(_.addScope(environmentName, scopeName))
        case Left(e) => Left(e)
      }
  }

  def scopeRemovingApplicationEnricher(
    environmentName: EnvironmentName,
    original: Application,
    idmsConnector: IdmsConnector,
    scopeName: String,
    clientId: Option[String] = None
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    Future
      .sequence(
        original
          .getCredentials(environmentName)
          .filter(credential => clientId.isEmpty || clientId.contains(credential.clientId))
          .map(
            credential =>
              idmsConnector
                .deleteClientScope(environmentName, credential.clientId, scopeName)
                .map {
                  case Right(_) => Right(())
                  case Left(e) if e.issue.equals(ClientNotFound) => Right(())
                  case Left(e) => Left(e)
                }
          )
      )
      .map(useFirstException)
      .map(_.map(_ =>
        (application: Application) => {
          if (updateScopes(environmentName, application, clientId)) {
            environmentName match {
              case Primary => application.removeScope(Primary, scopeName)
              case Secondary => application.removeScope(Secondary, scopeName)
            }
          }
          else {
            application
          }
        }
      ))

  }

  private def updateScopes(environmentName: EnvironmentName, application: Application, clientId: Option[String]) = {
    clientId match {
      case Some(clientId) =>
        application
          .getMasterCredential(environmentName)
          .map(_.clientId)
          .contains(clientId)
      case _ => true
    }
  }

}
