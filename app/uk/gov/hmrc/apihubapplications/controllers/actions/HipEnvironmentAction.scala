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

package uk.gov.hmrc.apihubapplications.controllers.actions

import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.requests.HipEnvironmentRequest
import scala.concurrent.{ExecutionContext, Future}
import com.google.inject.Inject
import play.api.mvc.{ActionFilter, ActionRefiner, Request, Result}
import play.api.mvc.Results.NotFound


trait HipEnvironmentAction extends ActionRefiner[Request, HipEnvironmentRequest]

trait HipEnvironmentActionProvider {
  def apply(environmentName: String)(implicit ec: ExecutionContext): HipEnvironmentAction
}

class HipEnvironmentActionProviderImpl @Inject(hipEnvironments: HipEnvironments) extends HipEnvironmentActionProvider {
  def apply(environmentName: String)(implicit ec: ExecutionContext): HipEnvironmentAction = {
    new HipEnvironmentAction {
      override protected def refine[A](request: Request[A]): Future[Either[Result, HipEnvironmentRequest[A]]] = {
        hipEnvironments.forUrlPathParameter(environmentName) match {
          case Some(hipEnvironment) => Future.successful(Right(HipEnvironmentRequest(request, hipEnvironment)))
          case None => Future.successful(Left(NotFound))
        }
      }

      override protected def executionContext: ExecutionContext = ec
    }
  }

}
