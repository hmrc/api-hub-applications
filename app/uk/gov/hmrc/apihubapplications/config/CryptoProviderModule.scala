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

package uk.gov.hmrc.apihubapplications.config

import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.apihubapplications.crypto.NoCrypto
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, SymmetricCryptoFactory}

class CryptoProviderModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[?]] =
    Seq(
      bind[Encrypter with Decrypter].qualifiedWith("aes").toInstance(aesCryptoInstance(configuration))
    )

  private def aesCryptoInstance(configuration: Configuration): Encrypter with Decrypter = if (
    configuration.underlying.getBoolean("fieldLevelEncryption.enable")
  )
    SymmetricCryptoFactory.aesCryptoFromConfig("fieldLevelEncryption", configuration.underlying)
  else
    NoCrypto

}
