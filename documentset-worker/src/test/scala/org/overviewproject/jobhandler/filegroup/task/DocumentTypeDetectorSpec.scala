package org.overviewproject.jobhandler.filegroup.task

import java.io.BufferedInputStream
import java.io.InputStream

import org.overviewproject.jobhandler.filegroup.task.DocumentTypeDetector._
import org.overviewproject.mime_types.MimeTypeDetector
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class DocumentTypeDetectorSpec extends Specification with Mockito {

  "DocumentTypeDetector" should {

    "detect document type" in new PdfScope {
      documentTypeDetector.detect(filename, stream) must be equalTo PdfDocument
    }

    "attempt parent type" in new PlainTextScope {
      documentTypeDetector.detect(filename, stream) must be equalTo TextDocument
    }

    "return UnsupportedDocument if mimetype is not handled" in new UnsupportedScope {
      documentTypeDetector.detect(filename, stream) must be equalTo UnsupportedDocument(filename, unsupportedMimeType)
    }
  }

  trait DetectorScope extends Scope {

    val mockMimeTypeDetector = smartMock[MimeTypeDetector]
    val stream = smartMock[InputStream]
    
    val filename = "file name"

    class TestDocumentTypeDetector(mimeType: String) extends DocumentTypeDetector {
      override protected val mimeTypeToDocumentType = Map(
        ("application/x-pdf" -> PdfDocument),
        ("text/*" -> TextDocument))

      override protected val mimeTypeDetector = mockMimeTypeDetector

      mimeTypeDetector.detectMimeType(be(filename), org.mockito.Matchers.isA(classOf[BufferedInputStream])) returns mimeType
      mimeTypeDetector.getMaxGetBytesLength returns 5
    }
  }

  trait PdfScope extends DetectorScope {
    val documentTypeDetector = new TestDocumentTypeDetector("application/x-pdf")
  }

  trait PlainTextScope extends DetectorScope {
    val documentTypeDetector = new TestDocumentTypeDetector("text/plain")
  }

  trait UnsupportedScope extends DetectorScope {
    val unsupportedMimeType = "image/png"
    val documentTypeDetector = new TestDocumentTypeDetector(unsupportedMimeType)
  }

}