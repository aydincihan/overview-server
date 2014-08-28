package org.overviewproject.jobhandler.filegroup

import scala.annotation.tailrec

import akka.actor.Actor
import akka.actor.Props

import org.overviewproject.database.Database
import org.overviewproject.database.orm.finders.DocumentSetCreationJobFinder
import org.overviewproject.database.orm.stores.DocumentSetCreationJobStore
import org.overviewproject.tree.orm.DocumentSetCreationJobState._

object ProgressReporterProtocol {
  case class StartJob(jobId: Long, numberOfTasks: Int)
  case class CompleteJob(jobId: Long)

  case class StartJobStep(jobId: Long, numberOfTasksInStep: Int, progressFraction: Double)
  case class CompleteJobStep(jobId: Long)

  case class StartTask(jobId: Long, taskId: Long)
  case class CompleteTask(jobId: Long, taskId: Long)
}

case class JobProgress(numberOfTasks: Int, tasksStarted: Int = 0, completedStepsFraction: Double = 0.0,
                       currentStepFraction: Double = 1.00, currentStep: Option[JobProgress] = None) {

  def startJobStep(numberOfTasksInStep: Int, progressFractionInStep: Double): JobProgress =
    updateCurrentStep(copy(currentStep = Some(JobProgress(numberOfTasksInStep)),
      tasksStarted = tasksStarted + 1,
      currentStepFraction = progressFractionInStep))(_.startJobStep(numberOfTasksInStep, progressFractionInStep))

  def completeJobStep: JobProgress = currentStep.fold(this)(
    _.updateCurrentStep(copy(currentStep = None,
      completedStepsFraction = completedStepsFraction + currentStepFraction))(_.completeJobStep))

  def startTask: JobProgress = updateCurrentStep(copy(tasksStarted = tasksStarted + 1))(_.startTask)

  def completeTask: JobProgress =
    updateCurrentStep(copy(completedStepsFraction = tasksStarted.toDouble / numberOfTasks.toDouble))(_.completeTask)

  def stepInProgress: JobProgress = currentStep.fold(this)(_.stepInProgress)

  def fraction: Double = currentStep.fold(completedStepsFraction)(completedStepsFraction + currentStepFraction * _.fraction)

  private def updateCurrentStep(updateJobStep: => JobProgress)(f: JobProgress => JobProgress): JobProgress =
    currentStep.fold(updateJobStep)(p => copy(currentStep = Some(f(p))))

}

trait ProgressReporter extends Actor {
  import ProgressReporterProtocol._

  private var jobProgress: Map[Long, JobProgress] = Map.empty

  protected val storage: Storage

  protected trait Storage {
    def updateProgress(jobId: Long, fraction: Double, description: String): Unit
    def updateJobState(jobId: Long, state: DocumentSetCreationJobState): Unit
  }

  def receive = {
    case StartJob(jobId, numberOfTasks) => updateProgress(jobId, JobProgress(numberOfTasks))
    case CompleteJob(jobId) => completeJob(jobId)

    case StartJobStep(jobId, numberOfTasksInStep, progressFraction) => startJobStep(jobId, numberOfTasksInStep, progressFraction)
    case CompleteJobStep(jobId) => updateTaskForJob(jobId, _.completeJobStep)

    case StartTask(jobId, taskId) => updateTaskForJob(jobId, _.startTask)
    case CompleteTask(jobId, taskId) => updateTaskForJob(jobId, _.completeTask)
  }

  private def description(progress: JobProgress): String =
    s"processing_files:${progress.stepInProgress.tasksStarted}:${progress.stepInProgress.numberOfTasks}"

  private def completeJob(jobId: Long): Unit = jobProgress -= jobId

  private def updateTaskForJob(jobId: Long, updateFunction: JobProgress => JobProgress): Unit =
    jobProgress.get(jobId).map { p => updateProgress(jobId, updateFunction(p)) }

  private def updateProgress(jobId: Long, progress: JobProgress): Unit = {
    jobProgress += (jobId -> progress)
    storage.updateProgress(jobId, progress.fraction, description(progress))
  }

  private def startJobStep(jobId: Long, numberOfTasksInStep: Int, progressFraction: Double): Unit =
    jobProgress.get(jobId).map { p => jobProgress += (jobId -> p.startJobStep(numberOfTasksInStep, progressFraction)) }

  private def completeJobStep(jobId: Long): Unit =
    jobProgress.get(jobId).map { p => jobProgress += (jobId -> p.completeJobStep) }
}

class ProgressReporterImpl extends ProgressReporter {
  class DatabaseStorage extends Storage {
    override def updateProgress(jobId: Long, fraction: Double, description: String): Unit = Database.inTransaction {
      DocumentSetCreationJobFinder.byDocumentSetAndStateForUpdate(jobId, TextExtractionInProgress).headOption.map { job =>
        val update = job.copy(fractionComplete = fraction, statusDescription = description)
        DocumentSetCreationJobStore.insertOrUpdate(update)
      }
    }

    override def updateJobState(jobId: Long, state: DocumentSetCreationJobState): Unit = Database.inTransaction {
      DocumentSetCreationJobFinder.byDocumentSetAndStateForUpdate(jobId, TextExtractionInProgress).headOption.map { job =>
        val update = job.copy(state = state)
        DocumentSetCreationJobStore.insertOrUpdate(update)
      }
    }
  }

  override protected val storage = new DatabaseStorage
}

object ProgressReporter {
  def apply(): Props = Props[ProgressReporterImpl]
}