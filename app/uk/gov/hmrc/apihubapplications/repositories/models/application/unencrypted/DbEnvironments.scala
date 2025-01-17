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

package uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.application.Environments

case class DbEnvironments(
  primary: DbEnvironment,
  secondary: DbEnvironment,
) {

  def toModel(dbApplication: DbApplication, hipEnvironments: HipEnvironments): Environments =
    Environments(
      primary = primary.toModel(dbApplication, hipEnvironments.productionEnvironment.id),
      secondary = secondary.toModel(dbApplication, hipEnvironments.deploymentEnvironment.id)
    )

}

object DbEnvironments {

  def apply(environments: Environments): DbEnvironments =
    DbEnvironments(
      primary = DbEnvironment(environments.primary),
      secondary = DbEnvironment(environments.secondary)
    )

  implicit val formatDbEnvironments: Format[DbEnvironments] = Json.format[DbEnvironments]

}
