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
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsService @Inject()(repository: ApplicationsRepository, clock: Clock)
                                                         (implicit ec: ExecutionContext) extends Logging{

  def registerApplication(newApplication: NewApplication): Future[Application] = {
    repository.insert(
      Application(newApplication, clock)
        .assertTeamMember(newApplication.createdBy.email)
    )
  }

  def findAll(): Future[Seq[Application]] = {
    repository.findAll()
  }

  def filter(teamMemberEmail: String): Future[Seq[Application]] = {
    repository.filter(teamMemberEmail)
  }

  def findById(id: String): Future[Option[Application]] = {
    repository.findById(id)
  }

  def getApplicationsWithPendingScope(): Future[Seq[Application]] = findAll().map(_.filter(_.hasProdPendingScope))

  def addScopes(applicationId: String, newScopes: Seq[NewScope]): Future[Boolean] =
    repository.findById(applicationId).flatMap {
      case Some(application) =>
        val appWithNewScopes = newScopes.foldLeft[Application](application)((outerApp, newScope) => {
          newScope.environments.foldLeft[Application](outerApp)((innerApp, envName) =>
            innerApp.addScopes(envName, Seq(newScope.name))
          )
        }).copy(lastUpdated = LocalDateTime.now(clock))

        repository.update(appWithNewScopes)
      case None => Future.successful(false)
    }

  def setPendingProdScopeStatusToApproved(applicationId: String, scopeName:String): Future[Option[Boolean]] = {
    repository.findById(applicationId).flatMap { _ match {
        case Some(application)  =>
          if (application.getProdScopes.exists(scope => scope.name == scopeName && scope.status == Pending)){
            val updatedApp: Application = application.setProdScopes(
              application.environments.prod.scopes.map(scope =>
                if (scope.name == scopeName) scope.copy(status = Approved) else scope
              )).copy(lastUpdated = LocalDateTime.now(clock))
            repository.update(updatedApp).map(Some(_))
          }else{
            Future.successful(Some(false))
          }

        case None => Future.successful(None)
      }
    }
  }
}
