package org.overviewproject.jobhandler.filegroup.task.step

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.overviewproject.database.SlickSessionProvider
import org.overviewproject.models.Document
import org.overviewproject.models.TempDocumentSetFile
import org.overviewproject.util.BulkDocumentWriter
import org.overviewproject.searchindex.ElasticSearchIndexClient
import org.overviewproject.searchindex.TransportIndexClient

/**
 * Write documents to the database and index them in ElasticSearch.
 */
trait WriteDocuments extends UploadedFileProcessStep {

  override protected val documentSetId: Long
  override protected val filename: String

  protected val storage: Storage
  protected val bulkDocumentWriter: BulkDocumentWriter
  protected val searchIndex: ElasticSearchIndexClient

  protected val documents: Seq[Document]

  override protected def doExecute: Future[TaskStep] = {
    val write = writeDocuments
    val index = indexDocuments

    for {
      writeResult <- write
      indexResult <- index
      deleted <- storage.deleteTempDocumentSetFiles(documents)
    } yield FinalStep

  }

  protected trait Storage {
    def deleteTempDocumentSetFiles(documents: Seq[Document]): Future[Int]
  }

  private def writeDocuments: Future[Unit] =
    for {
      docsAdded <- Future.sequence(documents.map(bulkDocumentWriter.addAndFlushIfNeeded)) // FIXME: should be done in serial
      batchFlushed <- bulkDocumentWriter.flush
    } yield {}

  private def indexDocuments: Future[Unit] = searchIndex.addDocuments(documents)

}

object WriteDocuments {

  def apply(documentSetId: Long, filename: String, documents: Seq[Document]): WriteDocuments =
    new WriteDocumentsImpl(documentSetId, filename, documents)

  private class WriteDocumentsImpl(
    override protected val documentSetId: Long,
    override protected val filename: String,
    override protected val documents: Seq[Document]) extends WriteDocuments {

    override protected val bulkDocumentWriter = BulkDocumentWriter.forDatabaseAndSearchIndex // Thread safe?
    override protected val searchIndex = TransportIndexClient.singleton

    override protected val storage: Storage = new SlickStorage

    private class SlickStorage extends Storage with SlickSessionProvider {
      import org.overviewproject.database.Slick.simple._
      import org.overviewproject.models.tables.TempDocumentSetFiles

      override def deleteTempDocumentSetFiles(documents: Seq[Document]): Future[Int] = db { implicit session =>
        val fileIds = documents.flatMap(_.fileId)
        TempDocumentSetFiles.filter(_.fileId inSet fileIds).delete
      }
    }
  }
}