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

package uk.gov.hmrc.apihubapplications.testonly

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.http.{HttpResponse, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestConnectivityController @Inject()(identify: IdentifierAction,
                                           httpClient: HttpClientV2,
                                           cc: ControllerComponents)(implicit ec: ExecutionContext)
  extends BackendController(cc) with Logging {

  def connectToHip: Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      val response: Future[HttpResponse] = httpClient
        .get(url"https://hip.ws.ibt.hmrc.gov.uk/_health")
        .execute

      response
        .map { resp =>
          val respString = s"status: ${resp.status}, body: ${resp.body}, headers: ${resp.headers.toString}"
          logger.info(s"Test Connectivity received response: $respString")
          respString
        }
        .recover {
          case throwable: Throwable =>
            logger.info(s"Test Connectivity received error: $throwable")
            throwable.toString
        }
        .map(responseString => Ok(responseString))
  }

}
