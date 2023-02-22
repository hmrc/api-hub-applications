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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName, NewApplication, NewScope}
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsService @Inject()(repository: ApplicationsRepository, clock: Clock)(implicit ec: ExecutionContext) extends Logging{

  def registerApplication(newApplication: NewApplication): Future[Application] = {
    repository.insert(
      Application(newApplication, clock)
        .assertTeamMember(newApplication.createdBy.email)
    )
  }

  def findAll(): Future[Seq[Application]] = {
    repository.findAll()
  }

  def findById(id: String): Future[Option[Application]] = {
    repository.findById(id)
  }

  def addScopes(applicationId: String, newScopes: Seq[NewScope]): Future[Option[Boolean]] =
    repository.findById(applicationId).flatMap {
      case Some(application) =>
        val envScopes: Seq[(EnvironmentName, String)] = newScopes.foldLeft(Seq.empty[(EnvironmentName,String)])((envToScopes, newScope) =>envToScopes++newScope.environments.map(env => (env,newScope.name)))
        val envToScopesMap: Map[EnvironmentName, Seq[String]] = envScopes.groupBy(_._1).map(kv => (kv._1, kv._2.map(newScope => newScope._2)))
        val updatedApp: Application = envToScopesMap.foldLeft(application)((app, envToScopes) => app.addScopes(envToScopes._1, envToScopes._2))
        repository.update(updatedApp).map(app => Some(app))
      case None => Future.successful(None)
    }


  def setScope(applicationId: String, env: String, scopeName: String, updateStatus: UpdateScopeStatus): Future[Option[Boolean]] = {
    repository.findById(applicationId).flatMap { _ match {
        case Some(application) =>
          val updatedApp: Option[Application] = env match {
            case "dev" =>
              if (application.environments.dev.scopes.exists(scope => scope.name == scopeName)) {
                Some(application.setDevScopes(application.environments.dev.scopes.map(scope => if (scope.name == scopeName) scope.copy(status = updateStatus.status) else scope)))
              } else {
                None
              }

            case "test" =>
              if (application.environments.test.scopes.exists(scope => scope.name == scopeName)) {
                Some(application.setTestScopes(application.environments.test.scopes.map(scope => if (scope.name == scopeName) scope.copy(status = updateStatus.status) else scope)))
              } else {
                None
              }

            case "preProd" =>
              if (application.environments.preProd.scopes.exists(scope => scope.name == scopeName)) {
                Some(application.setPreProdScopes(application.environments.preProd.scopes.map(scope => if (scope.name == scopeName) scope.copy(status = updateStatus.status) else scope)))
              } else {
                None
              }

            case "prod" =>
              if (application.environments.prod.scopes.exists(scope => scope.name == scopeName)) {
                Some(application.setProdScopes(application.environments.prod.scopes.map(scope => if (scope.name == scopeName) scope.copy(status = updateStatus.status) else scope)))
              } else {
                None
              }

            case _ => None
          }
          updatedApp match {
            case Some(app) => repository.update(app).map(Some(_))
            case None => Future.successful(None)
          }
        case None => Future.successful(Some(false))
      }
    }
  }
}
