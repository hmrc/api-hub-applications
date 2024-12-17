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

import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
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
                                              idmsConnector: IdmsConnector,
                                              hipEnvironments: HipEnvironments,
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
        original.getCredentials(hipEnvironments.forEnvironmentName(Secondary)).map {
          credential =>
            idmsConnector.fetchClient(hipEnvironments.forEnvironmentName(Secondary), credential.clientId)
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
                                         idmsConnector: IdmsConnector,
                                         hipEnvironments: HipEnvironments,
                                       )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    def buildEnricher(clientScopes: Seq[ClientScope]): ApplicationEnricher = {
      (application: Application) => {
        application.setScopes(
          hipEnvironments.forEnvironmentName(Secondary),
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
          .fetchClientScopes(hipEnvironments.forEnvironmentName(Secondary), credential.clientId)
          .map {
            case Right(clientScopes) => Right(buildEnricher(clientScopes))
            case Left(e: IdmsException) => Right(buildIssuesEnricher(e))
          }
      case None => Future.successful(Right(noOpApplicationEnricher))
    }
  }

  def primaryScopeApplicationEnricher(
                                       original: Application,
                                       idmsConnector: IdmsConnector,
                                       hipEnvironments: HipEnvironments,
                                     )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    def buildEnricher(clientScopes: Seq[ClientScope]): ApplicationEnricher = {
      (application: Application) => {
        application.setScopes(
          hipEnvironments.forEnvironmentName(Primary),
          clientScopes.map(clientScope => Scope(clientScope.clientScopeId))
        )
      }
    }

    def buildIssuesEnricher(idmsException: IdmsException): ApplicationEnricher = {
      (application: Application) => {
        application.addIssue(Issues.primaryScopesNotFound(idmsException))
      }
    }

    original.getMasterCredential(hipEnvironments.forEnvironmentName(Primary)) match {
      case Some(credential) =>
        idmsConnector
          .fetchClientScopes(hipEnvironments.forEnvironmentName(Primary), credential.clientId)
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
                                           )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
      idmsConnector.createClient(hipEnvironment, Client(original)).map {
        case Right(clientResponse) =>
          Right(
            (application: Application) => {
              application.addCredential(hipEnvironment, clientResponse.asNewCredential(clock, hipEnvironment))
            }
          )
        case Left(e) => Left(e)
      }
  }

  def scopeAddingApplicationEnricher(
                                      hipEnvironment: HipEnvironment,
                                      original: Application,
                                      idmsConnector: IdmsConnector,
                                      scopeName: String
                                    )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.sequence(
        original
          .getCredentials(hipEnvironment)
          .map(
            credential =>
              idmsConnector.addClientScope(hipEnvironment, credential.clientId, scopeName)
          )
      )
      .map(useFirstException)
      .map {
        case Right(_) => Right(_.addScope(hipEnvironment, scopeName))
        case Left(e) => Left(e)
      }
  }

  def scopeRemovingApplicationEnricher(
    hipEnvironment: HipEnvironment,
    original: Application,
    idmsConnector: IdmsConnector,
    scopeName: String,
    clientId: Option[String] = None
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    Future
      .sequence(
        original
          .getCredentials(hipEnvironment)
          .filter(credential => clientId.isEmpty || clientId.contains(credential.clientId))
          .map(
            credential =>
              idmsConnector
                .deleteClientScope(hipEnvironment, credential.clientId, scopeName)
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
          if (updateScopes(hipEnvironment, application, clientId)) {
              application.removeScope(hipEnvironment, scopeName)
          }
          else {
            application
          }
        }
      ))

  }

  private def updateScopes(hipEnvironment: HipEnvironment, application: Application, clientId: Option[String]) = {
    clientId match {
      case Some(clientId) =>
        application
          .getMasterCredential(hipEnvironment)
          .map(_.clientId)
          .contains(clientId)
      case _ => true
    }
  }

}
