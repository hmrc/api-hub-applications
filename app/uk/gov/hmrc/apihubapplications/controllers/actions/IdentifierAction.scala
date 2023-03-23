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

package uk.gov.hmrc.apihubapplications.controllers.actions

import com.google.inject.Inject
import play.api.mvc._
import uk.gov.hmrc.internalauth.client._

import scala.concurrent.{ExecutionContext, Future}

trait IdentifierAction extends ActionBuilder[Request, AnyContent] with ActionFunction[Request, Request]

class AuthenticatedIdentifierAction @Inject()(val parser: BodyParsers.Default,
                                              auth: BackendAuthComponents
                                             )(implicit val executionContext: ExecutionContext) extends IdentifierAction {

  val permission = Predicate.Permission(
    resource = Resource.from("api-hub-applications", resourceLocation = "*"),
    action = IAAction("WRITE") // must be one of READ, WRITE or DELETE
  )

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    auth.authorizedAction(
      permission,
      onUnauthorizedError = Future.successful(Results.Unauthorized),
      onForbiddenError = Future.successful(Results.Forbidden)).invokeBlock(request, block)
  }
}
