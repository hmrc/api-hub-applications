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

import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, Approved, Pending, Primary, Scope, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

trait ApplicationEnricher {

  def enrich(application: Application): Application

}

object ApplicationEnrichers {

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

  private val noOpApplicationEnricher = new ApplicationEnricher {
    override def enrich(application: Application): Application = {
      application
    }
  }

  def secondaryCredentialApplicationEnricher(
    original: Application,
    idmsConnector: IdmsConnector
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.sequence(
      original.getSecondaryCredentials.map {
        credential =>
          idmsConnector.fetchClient(Secondary, credential.clientId)
      }
    )
      .map(useFirstException)
      .map {
        case Right(clientResponses) =>
          Right(
            (application: Application) => {
              application.setSecondaryCredentials(
                clientResponses.map(_.asCredentialWithSecret())
              )
            }
          )
        case Left(e) => Left(e)
      }
  }

  def secondaryScopeApplicationEnricher(
    original: Application,
    idmsConnector: IdmsConnector
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    // Note that this enricher processes the first secondary credential only
    // There is no definition of how to combine scopes from multiple credentials
    // into a single collection of scopes.
    original.getSecondaryCredentials.headOption
      .map {
        credential =>
          idmsConnector.fetchClientScopes(Secondary, credential.clientId)
            .map {
              case Right(clientScopes) =>
                Right(
                  new ApplicationEnricher {
                    override def enrich(application: Application): Application = {
                      application.setSecondaryScopes(
                        clientScopes.map(clientScope => Scope(clientScope.clientScopeId, Approved))
                      )
                    }
                  }
                )
              case Left(e) => Left(e)
            }
      }
      .getOrElse(Future.successful(Right(noOpApplicationEnricher)))
  }

  def primaryScopeApplicationEnricher(
    original: Application,
    idmsConnector: IdmsConnector
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    // Note that this enricher processes the first primary credential only
    // There is no definition of how to combine scopes from multiple credentials
    // into a single collection of scopes.
    original.getPrimaryCredentials.headOption
      .map {
        credential =>
          idmsConnector.fetchClientScopes(Primary, credential.clientId)
            .map {
              case Right(clientScopes) =>
                Right(
                  new ApplicationEnricher {
                    override def enrich(application: Application): Application = {
                      val approved = clientScopes.map(clientScope => Scope(clientScope.clientScopeId, Approved))
                      val pending = application.getPrimaryScopes.filter(scope => scope.status == Pending)
                      application.setPrimaryScopes(
                        approved ++ pending
                      )
                    }
                  }
                )
              case Left(e) => Left(e)
            }
      }
      .getOrElse(Future.successful(Right(noOpApplicationEnricher)))
  }

}
