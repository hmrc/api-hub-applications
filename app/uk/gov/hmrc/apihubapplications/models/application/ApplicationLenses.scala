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

package uk.gov.hmrc.apihubapplications.models.application

import uk.gov.hmrc.apihubapplications.models.Lens

object ApplicationLenses {

  val applicationEnvironments: Lens[Application, Environments] =
    Lens[Application, Environments](
      get = _.environments,
      set = (application, environments) => application.copy(environments = environments)
    )

  val environmentScopes: Lens[Environment, Seq[Scope]] =
    Lens[Environment, Seq[Scope]](
      get = _.scopes,
      set = (environment, scopes) => environment.copy(scopes = scopes)
    )

  val environmentProd: Lens[Environments, Environment] =
    Lens[Environments, Environment](
      get = _.prod,
      set = (environments, prod) => environments.copy(prod = prod)
    )

  val applicationProd: Lens[Application, Environment] =
    Lens.compose(applicationEnvironments, environmentProd)

  val applicationProdScopes: Lens[Application, Seq[Scope]] =
    Lens.compose(applicationProd, environmentScopes)

  val environmentPreProd: Lens[Environments, Environment] =
    Lens[Environments, Environment](
      get = _.preProd,
      set = (environments, preProd) => environments.copy(preProd = preProd)
    )

  val applicationPreProd: Lens[Application, Environment] =
    Lens.compose(applicationEnvironments, environmentPreProd)

  val applicationPreProdScopes: Lens[Application, Seq[Scope]] =
    Lens.compose(applicationPreProd, environmentScopes)

  val environmentTest: Lens[Environments, Environment] =
    Lens[Environments, Environment](
      get = _.test,
      set = (environments, test) => environments.copy(test = test)
    )

  val applicationTest: Lens[Application, Environment] =
    Lens.compose(applicationEnvironments, environmentTest)

  val applicationTestScopes: Lens[Application, Seq[Scope]] =
    Lens.compose(applicationTest, environmentScopes)

  val environmentDev: Lens[Environments, Environment] =
    Lens[Environments, Environment](
      get = _.dev,
      set = (environments, dev) => environments.copy(dev = dev)
    )

  val applicationDev: Lens[Application, Environment] =
    Lens.compose(applicationEnvironments, environmentDev)

  val applicationDevScopes: Lens[Application, Seq[Scope]] =
    Lens.compose(applicationDev, environmentScopes)

  implicit class ApplicationLensOps(application: Application) {

    def getProdScopes: Seq[Scope] =
      applicationProdScopes.get(application)

    def setProdScopes(scopes: Seq[Scope]): Application =
      applicationProdScopes.set(application, scopes)

    def addProdScope(scope: Scope): Application =
      applicationProdScopes.set(
        application,
        applicationProdScopes.get(application) :+ scope
      )

    def hasProdPendingScope: Boolean =
      applicationProdScopes.get(application)
        .exists(scope => scope.status == Pending)

    def getPreProdScopes: Seq[Scope] =
      applicationPreProdScopes.get(application)

    def setPreProdScopes(scopes: Seq[Scope]): Application =
      applicationPreProdScopes.set(application, scopes)

    def addPreProdScope(scope: Scope): Application =
      applicationPreProdScopes.set(
        application,
        applicationPreProdScopes.get(application) :+ scope
      )

    def getTestScopes: Seq[Scope] =
      applicationTestScopes.get(application)

    def setTestScopes(scopes: Seq[Scope]): Application =
      applicationTestScopes.set(application, scopes)

    def addTestScope(scope: Scope): Application =
      applicationTestScopes.set(
        application,
        applicationTestScopes.get(application) :+ scope
      )

    def getDevScopes: Seq[Scope] =
      applicationDevScopes.get(application)

    def setDevScopes(scopes: Seq[Scope]): Application =
      applicationDevScopes.set(application, scopes)

    def addDevScope(scope: Scope): Application =
      applicationDevScopes.set(
        application,
        applicationDevScopes.get(application) :+ scope
      )

  }

}