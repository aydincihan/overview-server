/**
 * *
 * JobHandler.scala
 *
 * Overview Project,June 2012
 * @author Jonas Karlsson
 */

import com.jolbox.bonecp._
import database.{ DatabaseConfiguration, DataSource, DB }
import java.sql.Connection 
import overview.clustering._
import overview.http.AsyncHttpRequest
import overview.util.{ ExceptionStatusMessage, JobRestarter, Logger }
import overview.util.Progress._
import persistence._
import persistence.DocumentSetCreationJobState._

object JobHandler {

  val asyncHttpRetriever: AsyncHttpRequest = new AsyncHttpRequest
  // Run a single job
  def handleSingleJob(j: PersistentDocumentSetCreationJob): Unit = {
    try {
      Logger.info("Handling job")
      j.state = InProgress
      DB.withConnection { implicit connection =>
        j.update
      }
      val documentSetId = j.documentSetId

      val documentWriter = new DocumentWriter(documentSetId)
      val nodeWriter = new NodeWriter(documentSetId)
      def progFn(prog: Progress) = {
        j.fractionComplete = prog.fraction
        j.statusDescription = Some(prog.status.toString)
        DB.withConnection { implicit connection =>
          j.update
        }
        Logger.info("PROGRESS: " + prog.fraction * 100 + "% done. " + prog.status + ", " + (if (prog.hasError) "ERROR" else "OK")); false
      }

      val (_, query) = DB.withConnection { implicit connection =>
        DocumentSetLoader.loadQuery(j.documentSetId).get
      }

      val dcSource = new DocumentCloudSource(asyncHttpRetriever,
	query, j.documentCloudUsername, j.documentCloudPassword)

      val indexer = new DocumentSetIndexer(dcSource, nodeWriter, documentWriter, progFn)

      Logger.info("Indexing query: " + query)
      val tree = indexer.BuildTree()

      DB.withConnection { implicit connection =>
        j.delete
      }

    } catch {
      case t: Throwable =>
        Logger.error("Job failed: " + t.toString + "\n" + t.getStackTrace.mkString("\n"))
        j.state = Error
        j.statusDescription = Some(ExceptionStatusMessage(t))
        DB.withConnection { implicit connection => j.update }
    }
  }

  // Run each job currently listed in the database
  def scanForJobs: Unit = {

    val submittedJobs: Seq[PersistentDocumentSetCreationJob] = DB.withConnection { implicit connection =>
      PersistentDocumentSetCreationJob.findJobsWithState(Submitted)
    }

    for (j <- submittedJobs) {
      handleSingleJob(j)
      System.gc()
    }
  }


  def restartInterruptedJobs(implicit c: Connection) {
    val interruptedJobs = PersistentDocumentSetCreationJob.findJobsWithState(InProgress)
    val restarter = new JobRestarter(new DocumentSetCleaner)
    
    restarter.restart(interruptedJobs)
  }
  
  def main(args: Array[String]) {

    val pollingInterval = 500 //milliseconds

    val config = new DatabaseConfiguration()
    val dataSource = new DataSource(config)

    DB.connect(dataSource)

    DB.withConnection { implicit connection =>
      restartInterruptedJobs
    }
    
    while (true) {
      scanForJobs
      Thread.sleep(pollingInterval)
    }
  }

}
