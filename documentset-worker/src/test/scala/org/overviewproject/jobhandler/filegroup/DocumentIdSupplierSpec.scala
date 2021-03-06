package org.overviewproject.jobhandler.filegroup

import org.specs2.mutable.Specification
import org.overviewproject.test.ActorSystemContext
import org.specs2.mutable.Before
import akka.actor.{ ActorRef, Props }
import akka.pattern.ask
import org.overviewproject.jobhandler.filegroup.DocumentIdSupplierProtocol._
import akka.util.Timeout
import org.specs2.time.NoTimeConversions
import scala.concurrent.duration._
import org.specs2.mock.Mockito
import akka.testkit.TestProbe

class DocumentIdSupplierSpec extends Specification with NoTimeConversions {

  "DocumentIdSupplier" should {

    "reply to request for ids" in new DocumentIdSupplierScope {
      implicit val t = timeout
    
      val response = documentIdSupplier.ask(RequestIds(documentSetId, numberOfIds))

      response must be_==(IdRequestResponse(documentIds)).await
    }

    "reply to multiple request for the same documentset" in new DocumentIdSupplierScope {
      implicit val t = timeout
          
      val response = documentIdSupplier.ask(RequestIds(documentSetId, numberOfIds))
      val nextResponse = documentIdSupplier.ask(RequestIds(documentSetId, numberOfIds))

      nextResponse must be_==(IdRequestResponse(nextDocumentIds)).await
    }

    "reply to requests for different documentsets" in new DocumentIdSupplierScope {
      implicit val t = timeout
    
      val response = documentIdSupplier.ask(RequestIds(documentSetId, numberOfIds))
      val responseToo = documentIdSupplier.ask(RequestIds(documentSetIdToo, numberOfIds))

      responseToo must be_==(IdRequestResponse(documentIdsToo)).await
    }
  }
  
  abstract class DocumentIdSupplierScope extends ActorSystemContext with Before with Mockito {
    val timeout = Timeout(1 second)
    val documentSetId = 1l
    val numberOfIds = 3

    val documentIds = Seq(1l, 2l, 3l)
    val nextDocumentIds = Seq(4l, 5l, 6l)

    val documentSetIdToo = 2l
    val documentIdsToo = Seq(7l, 8l)

    var documentIdSupplier: ActorRef = _

    override def before = {
      documentIdSupplier = system.actorOf(Props(new TestDocumentIdSupplier))
    }

    class TestDocumentIdSupplier extends DocumentIdSupplier {
      override protected def createDocumentIdGenerator(dsId: Long) = {
        val documentIdGenerator = smartMock[DocumentIdGenerator]

        dsId match {
          case `documentSetId` =>
            documentIdGenerator.nextIds(any) returns documentIds thenReturns nextDocumentIds
          case `documentSetIdToo` =>
            documentIdGenerator.nextIds(any) returns documentIdsToo
          case _ =>
            documentIdGenerator.nextIds(any) throws new RuntimeException("error")
        }
        documentIdGenerator

      }
    }
  }

}