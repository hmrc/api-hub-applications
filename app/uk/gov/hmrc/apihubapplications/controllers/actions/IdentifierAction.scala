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
import play.api.libs.typedmap.TypedKey
import play.api.mvc.*
import uk.gov.hmrc.apihubapplications.controllers.actions.AuthenticatedIdentifierAction.UserEmailKey
import uk.gov.hmrc.crypto.{ApplicationCrypto, Crypted}
import uk.gov.hmrc.internalauth.client.*

import scala.concurrent.{ExecutionContext, Future}

trait IdentifierAction extends ActionBuilder[Request, AnyContent] with ActionFunction[Request, Request]

class AuthenticatedIdentifierAction @Inject()(val parser: BodyParsers.Default,
                                              crypto: ApplicationCrypto,
                                              auth: BackendAuthComponents
                                             )(implicit val executionContext: ExecutionContext) extends IdentifierAction {

  private val permission = Predicate.Permission(
    resource = Resource.from("api-hub-applications", resourceLocation = "*"),
    action = IAAction("WRITE") // must be one of READ, WRITE or DELETE
  )

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    val decryptedEmail = request.headers.get("Encrypted-User-Email").flatMap(
      encryptedEmail =>
        try {
          Some(crypto.QueryParameterCrypto.decrypt(Crypted(encryptedEmail)).value)
        } catch {
          case _: SecurityException => None
        }
    )

    auth.authorizedAction(
      permission,
      onUnauthorizedError = Future.successful(Results.Unauthorized),
      onForbiddenError = Future.successful(Results.Forbidden)
    ).invokeBlock(
      decryptedEmail
        .map(
          email =>
            request.addAttr(UserEmailKey, email)
        )
        .getOrElse(request),
      block
    )
  }
}

object AuthenticatedIdentifierAction {
  val UserEmailKey: TypedKey[String] = TypedKey[String]("User-Email")
}
