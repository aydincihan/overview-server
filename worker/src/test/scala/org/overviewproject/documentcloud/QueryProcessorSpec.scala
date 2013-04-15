package org.overviewproject.documentcloud

import java.net.URLEncoder
import org.overviewproject.documentcloud.DocumentRetrieverProtocol.{ Start => StartRetriever }
import org.overviewproject.documentcloud.QueryProcessorProtocol.Start
import org.overviewproject.http.PublicRequest
import org.overviewproject.http.RequestQueueProtocol._
import org.overviewproject.test.{ ActorSystemContext, TestSimpleResponse }
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import org.specs2.time.NoTimeConversions
import akka.actor.{ Actor, ActorRef, Props }
import akka.testkit.TestActorRef
import scala.concurrent.Promise
import org.specs2.mutable.Before
import org.overviewproject.documentcloud.DocumentRetrieverProtocol.GetTextSucceeded
import scala.util.Success
import org.overviewproject.http.Credentials
import org.overviewproject.http.PrivateRequest

class ReportingActor(d: Document, receiver: ActorRef, listener: ActorRef) extends Actor {
  def receive = {
    case StartRetriever() => listener ! "got message"
  }
}

class SilentActor(d: Document, receiver: ActorRef) extends Actor {
  def receive = {
    case _ =>
  }
}

class CompletingActor(d: Document, receiver: ActorRef) extends Actor {
  def receive = {
    case StartRetriever() => receiver ! GetTextSucceeded(d, "text")
  }
}

class QueryProcessorSpec extends Specification with NoTimeConversions {

  "QueryProcessor" should {

    abstract class QueryContext extends ActorSystemContext with Before {
      val query = "query string"
      var queryInformation: QueryInformation = _

      def before = {
        queryInformation = new QueryInformation
      }

      def pageQuery(pageNum: Int, query: String): String = s"https://www.documentcloud.org/api/search.json?per_page=20&page=$pageNum&q=${URLEncoder.encode(query, "UTF-8")}"

      def emptyProcessDocument(d: Document, text: String): Unit = {}
      def createQueryProcessor(receiverCreator: (Document, ActorRef) => Actor, credentials: Option[Credentials] = None): Actor =
        new QueryProcessor(query, queryInformation, credentials, emptyProcessDocument, testActor, receiverCreator)
    }

    "request query result pages" in new QueryContext {
      val page1Result = jsonSearchResultPage(20, 1, 20)
      val page2Result = jsonSearchResultPage(20, 2, 10)

      val queryProcessor = TestActorRef(createQueryProcessor(new SilentActor(_, _)))

      queryProcessor ! Start()
      expectMsg(AddToFront(PublicRequest(pageQuery(1, query))))

      queryProcessor ! Result(TestSimpleResponse(200, page1Result))
      expectMsg(AddToFront(PublicRequest(pageQuery(2, query))))

      queryProcessor ! Result(TestSimpleResponse(200, page2Result))
      expectNoMsg
    }

    "spawn actors and send them query results" in new QueryContext {
      val numberOfDocuments = 10
      val result = jsonSearchResultPage(20, 1, numberOfDocuments)
      val queryProcessor = system.actorOf(Props(createQueryProcessor(new ReportingActor(_, _, testActor))))

      queryProcessor ! Start()
      queryProcessor ! Result(TestSimpleResponse(200, result))

      receiveN(numberOfDocuments)
    }

    "complete errors promise when all documents have been processed" in new QueryContext {
      val numberOfDocuments = 5
      val result = jsonSearchResultPage(numberOfDocuments, 1, numberOfDocuments)
      val queryProcessor = system.actorOf(Props(createQueryProcessor(new CompletingActor(_, _))))

      queryProcessor ! Start()
      queryProcessor ! Result(TestSimpleResponse(200, result))

      awaitCond(queryInformation.errors.isCompleted)
    }

    "complete documentsTotal promise after first page of result has been received" in new QueryContext {
      val numberOfDocuments = 5
      val numberOfPages = 2
      val result = jsonSearchResultPage(numberOfDocuments, numberOfPages, numberOfDocuments)
      val queryProcessor = system.actorOf(Props(createQueryProcessor(new SilentActor(_, _))))

      queryProcessor ! Start()
      queryProcessor ! Result(TestSimpleResponse(200, result))

      val totalFuture = queryInformation.documentsTotal.future

      awaitCond(totalFuture.isCompleted)
      totalFuture.value must beSome(Success(numberOfDocuments))
    }

    "send authenticated request if credentials are provided" in new QueryContext {
      val credentials = Credentials("username", "password")
      val queryProcessor = TestActorRef(createQueryProcessor(new SilentActor(_, _), Some(credentials)))

      queryProcessor ! Start()
      expectMsg(AddToFront(PrivateRequest(pageQuery(1, query), credentials)))
    }
  }

  def jsonSearchResultPage(total: Int, page: Int, numberOfDocuments: Int): String = {
    def documentJson(page: Int, index: Int): String = {
      val id = s"$page-$index"

      s"""
      {
        "id": "dcid-$id",
        "title": "title-$id",
        "access": "public",
        "pages": 1,
        "canonical_url": "http://url-$id"
      }
      """
    }

    val documents = Seq.tabulate(numberOfDocuments)(n => documentJson(page, n))
    val jsonDocumentArray = documents.mkString("[", ",", "]")

    s"""
      {
        "total": $total,
        "page": $page,
        "per_page": 20,
        "q": "Query",
        "documents": $jsonDocumentArray 
      }"""
  }
}