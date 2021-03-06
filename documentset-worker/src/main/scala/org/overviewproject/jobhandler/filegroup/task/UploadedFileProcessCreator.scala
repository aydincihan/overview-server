package org.overviewproject.jobhandler.filegroup.task

import scala.util.control.Exception.ultimately
import java.io.InputStream
import org.overviewproject.models.GroupedFileUpload
import org.overviewproject.jobhandler.filegroup.task.process.CreateDocumentFromPdfFile
import org.overviewproject.jobhandler.filegroup.task.process.CreateDocumentFromPdfPage
import org.overviewproject.jobhandler.filegroup.task.process.CreateDocumentFromConvertedFile
import org.overviewproject.jobhandler.filegroup.task.process.CreateDocumentsFromConvertedFilePages
import org.overviewproject.postgres.LargeObjectInputStream
import org.overviewproject.database.SlickSessionProvider
import akka.actor.ActorRef
import org.overviewproject.jobhandler.filegroup.task.process.UploadedFileProcess
import org.overviewproject.jobhandler.filegroup.task.DocumentTypeDetector._

trait UploadedFileProcessCreator {

  def create(uploadedFile: GroupedFileUpload, options: UploadProcessOptions,
             documentSetId: Long, documentIdSupplier: ActorRef): UploadedFileProcess =
    withLargeObjectInputStream(uploadedFile.contentsOid) { stream =>

      val name = uploadedFile.name

      val documentType = documentTypeDetector.detect(name, stream) 
      
      processMap.getProcess(documentType, options, documentSetId, name, documentIdSupplier)
    }

  private def withLargeObjectInputStream[T](oid: Long)(f: InputStream => T): T = {
    val stream = largeObjectInputStream(oid)

    ultimately(stream.close) {
      f(stream)
    }
  }

  protected val documentTypeDetector: DocumentTypeDetector
  protected def largeObjectInputStream(oid: Long): InputStream

  protected val processMap: ProcessMap

  protected trait ProcessMap {
    def getProcess(documentType: DocumentType, options: UploadProcessOptions,
                   documentSetId: Long, name: String,
                   documentIdSupplier: ActorRef): UploadedFileProcess
  }

}

object UploadedFileProcessCreator {

  def apply(): UploadedFileProcessCreator = new UploadedFileProcessCreatorImpl

  private class UploadedFileProcessCreatorImpl extends UploadedFileProcessCreator {
    override protected val documentTypeDetector = DocumentTypeDetector
    override protected def largeObjectInputStream(oid: Long) =
      new LargeObjectInputStream(oid, new SlickSessionProvider {})

    override protected val processMap: ProcessMap = new UploadedFileProcessMap
    
    private class UploadedFileProcessMap extends ProcessMap {

      override def getProcess(documentType: DocumentType, options: UploadProcessOptions,
                              documentSetId: Long, name: String,
                              documentIdSupplier: ActorRef): UploadedFileProcess =
        documentType match {
          case PdfDocument if options.splitDocument =>
            CreateDocumentFromPdfPage(documentSetId, name, documentIdSupplier)
          case PdfDocument =>
            CreateDocumentFromPdfFile(documentSetId, name, documentIdSupplier)
          case OfficeDocument if options.splitDocument =>
            CreateDocumentsFromConvertedFilePages(documentSetId, name, documentIdSupplier)
          case OfficeDocument =>
            CreateDocumentFromConvertedFile(documentSetId, name, documentIdSupplier)
        }
    }

  }
}
