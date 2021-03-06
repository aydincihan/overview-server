package org.overviewproject.jobhandler.filegroup

import akka.actor.Props
import akka.actor.ActorRef

class TestFileGroupJobQueue(
    tasks: Seq[Long],
    override protected val progressReporter: ActorRef,
    override protected val documentIdSupplier: ActorRef) extends FileGroupJobQueue {

  override protected val jobShepherdFactory = new TestJobShepherdFactory
  
  class TestJobShepherdFactory extends JobShepherdFactory {
    override def createShepherd(documentSetId: Long, job: FileGroupJob,
        taskQueue: ActorRef, progressReporter: ActorRef, documentIdSupplier: ActorRef): JobShepherd = job match {
      case CreateDocumentsJob(fileGroupId, options) =>
        new TestCreateDocumentsJobShepherd(documentSetId, fileGroupId, options,
            taskQueue, progressReporter, documentIdSupplier, tasks.toSet)
      case DeleteFileGroupJob(fileGroupId) => new DeleteFileGroupJobShepherd(documentSetId, fileGroupId, taskQueue)
    }
    
  }
}

object TestFileGroupJobQueue {
  def apply(tasks: Seq[Long], progressReporter: ActorRef, documentIdSupplier: ActorRef): Props =
    Props(new TestFileGroupJobQueue(tasks, progressReporter, documentIdSupplier))
}


