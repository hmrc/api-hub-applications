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

package testonly

import play.api.Logging
import play.api.libs.json.JsString
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.crypto.{ApplicationCrypto, Crypted}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject

class TestOnlyDecryptController@Inject()(
                                          cc: ControllerComponents,
                                          crypto: ApplicationCrypto,
                                        ) extends BackendController(cc) with Logging {
  def decrypt(email: String): Action[AnyContent] = Action {
      val decryptedEmailAddress = crypto.QueryParameterCrypto.decrypt(Crypted(email)).value
      logger.info(f" Decrypted email: $decryptedEmailAddress")
      val emailDecrypted = JsString(decryptedEmailAddress)
      Ok(emailDecrypted)
  }
}
