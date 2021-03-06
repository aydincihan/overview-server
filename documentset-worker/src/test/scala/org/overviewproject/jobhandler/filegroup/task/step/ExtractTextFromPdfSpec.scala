package org.overviewproject.jobhandler.filegroup.task.step

import scala.concurrent.ExecutionContext.Implicits.global
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import org.overviewproject.models.Document
import org.specs2.mock.Mockito
import org.overviewproject.models.File
import scala.concurrent.Future
import org.overviewproject.jobhandler.filegroup.task.PdfDocument

class ExtractTextFromPdfSpec extends Specification with Mockito {

  "ExtractTextFromPdf" should {

    "generate document with text" in new PdfFileScope {

      val r = extractTextFromPdf.execute.map {
        case NextStep(d) => d
      }

      r must be_==(List(PdfFileDocumentData(fileName, fileId, text))).await
    }

    "return failure on error" in new FailingTextExtractionScope {
      val r = extractTextFromPdf.execute

      r.value must beSome(beFailedTry[TaskStep])
    }
  }

  trait PdfFileScope extends Scope {
    case class DocumentInfo(documentSetId: Long, title: String, fileId: Option[Long], text: String)

    val viewLocation = "view location"
    val fileName = "file name"
    val fileId = 10l
    val documentFile = smartMock[File]
    documentFile.id returns fileId
    documentFile.name returns fileName
    documentFile.viewLocation returns viewLocation

    val text = "file text"
    val pdfDocument = setupPdfDocument

    val extractTextFromPdf = new TestExtractFromPdf

    def setupPdfDocument = {
      val d = smartMock[PdfDocument]

      d.text returns text
    }

    case class NextStep(document: Seq[DocumentData]) extends TaskStep {
      override protected def doExecute = Future.successful(this)
    }

    class TestExtractFromPdf extends ExtractTextFromPdf {
      override protected val documentSetId = 1l
      override protected val file = documentFile
      override protected val nextStep = { documentData => NextStep(documentData) }
      override protected val pdfProcessor = smartMock[PdfProcessor]

      pdfProcessor.loadFromBlobStorage(viewLocation) returns pdfDocument

    }

  }

  trait FailingTextExtractionScope extends PdfFileScope {

    override def setupPdfDocument = {
      val d = smartMock[PdfDocument]

      d.text throws new RuntimeException("failed")
    }
  }

}