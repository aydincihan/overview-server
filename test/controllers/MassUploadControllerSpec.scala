package controllers

import java.util.UUID
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.libs.iteratee.{Enumerator,Iteratee}
import play.api.mvc.{EssentialAction,Results}
import play.api.test.FakeRequest
import scala.concurrent.Future

import controllers.auth.{AuthorizedRequest,SessionFactory}
import controllers.backend.{DocumentSetBackend,FileGroupBackend,GroupedFileUploadBackend}
import models.{ Session, User }
import org.overviewproject.models.{DocumentSet,FileGroup,GroupedFileUpload}
import org.overviewproject.tree.orm.{DocumentSet=>DeprecatedDocumentSet,DocumentSetCreationJob}
import org.overviewproject.tree.DocumentSetCreationJobType._
import org.overviewproject.tree.orm.DocumentSetCreationJobState._

class MassUploadControllerSpec extends ControllerSpecification {
  trait BaseScope extends Scope {
    val mockDocumentSetBackend = smartMock[DocumentSetBackend]
    val mockFileGroupBackend = smartMock[FileGroupBackend]
    val mockUploadBackend = smartMock[GroupedFileUploadBackend]
    val mockSessionFactory = smartMock[SessionFactory]
    val mockStorage = smartMock[MassUploadController.Storage]
    val mockMessageQueue = smartMock[MassUploadController.MessageQueue]
    val mockUploadIterateeFactory = mock[(GroupedFileUpload,Long) => Iteratee[Array[Byte],Unit]]

    val controller = new MassUploadController {
      override val documentSetBackend = mockDocumentSetBackend
      override val fileGroupBackend = mockFileGroupBackend
      override val groupedFileUploadBackend = mockUploadBackend
      override val storage = mockStorage
      override val messageQueue = mockMessageQueue
      override val sessionFactory = mockSessionFactory
      override val uploadIterateeFactory = mockUploadIterateeFactory
    }

    val factory = org.overviewproject.test.factories.PodoFactory
  }

  "#create" should {
    trait CreateScope extends BaseScope {
      val user = User(id=123L, email="user@example.org")
      val session = Session(123L, "127.0.0.1")
      val baseRequest = FakeRequest().withHeaders("Content-Length" -> "20")
      lazy val request = new AuthorizedRequest(baseRequest, session, user)
      val enumerator: Enumerator[Array[Byte]] = Enumerator()
      lazy val action: EssentialAction = controller.create(UUID.randomUUID)
      lazy val result = enumerator.run(action(request))
    }

    "return a Result if ApiTokenFactory returns a Left[Result]" in new CreateScope {
      mockSessionFactory.loadAuthorizedSession(any, any) returns Future.successful(Left(Results.BadRequest))
      h.status(result) must beEqualTo(h.BAD_REQUEST)
    }

    "return Ok" in new CreateScope {
      val fileGroup = factory.fileGroup()
      val groupedFileUpload = factory.groupedFileUpload(size=20L, uploadedSize=10L)
      mockSessionFactory.loadAuthorizedSession(any, any) returns Future.successful(Right((session, user)))
      mockFileGroupBackend.findOrCreate(any) returns Future.successful(fileGroup)
      mockUploadBackend.findOrCreate(any) returns Future.successful(groupedFileUpload)
      mockUploadIterateeFactory(any, any) returns Iteratee.ignore[Array[Byte]]

      h.status(result) must beEqualTo(h.CREATED)
    }
  }

  "#show" should {
    trait ShowScope extends BaseScope {
      val guid = UUID.randomUUID()
      val userId = 123L
      val userEmail = "show-user@example.org"
      val fileGroupId = 234L

      val user = User(id=userId, email=userEmail)
      def fileGroup: Option[FileGroup] = Some(factory.fileGroup(id=fileGroupId))
      def groupedFileUpload: Option[GroupedFileUpload] = Some(factory.groupedFileUpload())

      mockFileGroupBackend.find(any, any) returns Future(fileGroup)
      mockUploadBackend.find(any, any) returns Future(groupedFileUpload)

      val request = new AuthorizedRequest(FakeRequest(), Session(userId, "127.0.0.1"), user)
      lazy val result = controller.show(guid)(request)
    }

    "find the upload" in new ShowScope {
      h.status(result)
      there was one(mockFileGroupBackend).find(userEmail, None)
      there was one(mockUploadBackend).find(fileGroupId, guid)
    }

    "return 404 if the GroupedFileUpload does not exist" in new ShowScope {
      override def groupedFileUpload = None
      h.status(result) must beEqualTo(h.NOT_FOUND)
    }

    "return 404 if the FileGroup does not exist" in new ShowScope {
      override def fileGroup = None
      h.status(result) must beEqualTo(h.NOT_FOUND)
    }

    "return 200 with Content-Length if upload is complete" in new ShowScope {
      override def groupedFileUpload = Some(factory.groupedFileUpload(size=1234, uploadedSize=1234))
      h.status(result) must beEqualTo(h.OK)
      h.header(h.CONTENT_LENGTH, result) must beSome("1234")
    }

    "return PartialContent with Content-Range if upload is not complete" in new ShowScope {
      override def groupedFileUpload = Some(factory.groupedFileUpload(size=2345, uploadedSize=1234))
      h.status(result) must beEqualTo(h.PARTIAL_CONTENT)
      h.header(h.CONTENT_RANGE, result) must beSome("bytes 0-1233/2345")
    }

    "return NoContent if uploaded file is empty" in new ShowScope {
      override def groupedFileUpload = Some(factory.groupedFileUpload(name="filename.abc", size=0, uploadedSize=0))
      h.status(result) must beEqualTo(h.NO_CONTENT)
      h.header(h.CONTENT_DISPOSITION, result) must beSome("attachment; filename=\"filename.abc\"")
    }

    "return NoContent if uploaded file was created but no bytes were ever added" in new ShowScope {
      override def groupedFileUpload = Some(factory.groupedFileUpload(name="filename.abc", size=1234, uploadedSize=0))
      h.status(result) must beEqualTo(h.NO_CONTENT)
      h.header(h.CONTENT_DISPOSITION, result) must beSome("attachment; filename=\"filename.abc\"")
    }
  }

  "#startClustering" should {
    trait StartClusteringScope extends BaseScope {
      val fileGroupName = "This becomes the Document Set Name"
      val lang = "sv"
      val splitDocuments = false
      val splitDocumentsString = s"$splitDocuments"
      val stopWords = "ignore these words"
      val importantWords = "important words?"
      def formData = Seq(
        "name" -> fileGroupName,
        "lang" -> lang,
        "split_documents" -> splitDocumentsString,
        "supplied_stop_words" -> stopWords,
        "important_words" -> importantWords
      )
      val documentSetId = 11L
      val job = factory.documentSetCreationJob()
      val user = User(id=123L, email="start-user@example.org")
      val fileGroup = factory.fileGroup(id=234L)
      val documentSet = factory.documentSet(id=documentSetId)

      mockFileGroupBackend.find(any, any) returns Future.successful(Some(fileGroup))
      mockFileGroupBackend.update(any, any) returns Future.successful(fileGroup.copy(completed=true))
      mockDocumentSetBackend.create(any, any) returns Future.successful(documentSet)
      mockStorage.createMassUploadDocumentSetCreationJob(any, any, any, any, any, any, any) returns job.toDeprecatedDocumentSetCreationJob
      mockMessageQueue.startClustering(any, any) returns Future.successful(())

      lazy val request = new AuthorizedRequest(FakeRequest().withFormUrlEncodedBody(formData: _*), Session(user.id, "127.0.0.1"), user)
      lazy val result = controller.startClustering()(request)
    }

    "redirect" in new StartClusteringScope {
      h.status(result) must beEqualTo(h.SEE_OTHER)
    }

    "create a DocumentSetCreationJob" in new StartClusteringScope {
      h.status(result)
      there was one(mockDocumentSetBackend).create(
        beLike[DocumentSet.CreateAttributes] { case attributes =>
          attributes.title must beEqualTo(fileGroupName)
        },
        beLike[String] { case s => s must beEqualTo(user.email) }
      )
      there was one(mockStorage).createMassUploadDocumentSetCreationJob(
        documentSetId, 234L, lang, false, stopWords, importantWords, true)
    }

    "send a ClusterFileGroup message" in new StartClusteringScope {
      h.status(result)
      there was one(mockMessageQueue).startClustering(job.toDeprecatedDocumentSetCreationJob, fileGroupName)
    }

    "set splitDocuments=true when asked" in new StartClusteringScope {
      override val splitDocumentsString = "true"
      h.status(result)
      there was one(mockStorage).createMassUploadDocumentSetCreationJob(
        documentSetId, 234L, lang, true, stopWords, importantWords, true)
    }

    "return NotFound if user has no FileGroup in progress" in new StartClusteringScope {
      mockFileGroupBackend.find(user.email, None) returns Future.successful(None)
      h.status(result) must beEqualTo(h.NOT_FOUND)
      there was no(mockDocumentSetBackend).create(any, any)
    }
  }

  "#startClusteringExistingDocumentSet" should {
    // XXX See how much of a hack this is? That's because we're combining two
    // actions in one, all the way down the stack (add files + cluster).
    //
    // TODO make adding files and clustering two different things, so we can do
    // everything with half the tests.
    trait StartClusteringExistingDocumentSetScope extends BaseScope {
      val lang = "sv"
      val splitDocuments = false
      val splitDocumentsString = s"$splitDocuments"
      val stopWords = "ignore these words"
      val importantWords = "important words?"
      def formData = Seq(
        "lang" -> lang,
        "split_documents" -> splitDocumentsString,
        "supplied_stop_words" -> stopWords,
        "important_words" -> importantWords
      )
      val documentSetId = 11L
      val job = factory.documentSetCreationJob()
      val user = User(id=123L, email="start-user@example.org")
      val fileGroup = factory.fileGroup(id=234L)
      val documentSet = factory.documentSet(id=documentSetId)

      mockFileGroupBackend.find(any, any) returns Future(Some(fileGroup))
      mockFileGroupBackend.update(any, any) returns Future(fileGroup.copy(completed=true))
      mockStorage.createMassUploadDocumentSetCreationJob(any, any, any, any, any, any, any) returns job.toDeprecatedDocumentSetCreationJob
      mockMessageQueue.startClustering(any, any) returns Future(())

      lazy val request = new AuthorizedRequest(FakeRequest().withFormUrlEncodedBody(formData: _*), Session(user.id, "127.0.0.1"), user)
      lazy val result = controller.startClusteringExistingDocumentSet(documentSetId)(request)
    }

    "redirect" in new StartClusteringExistingDocumentSetScope {
      h.status(result) must beEqualTo(h.SEE_OTHER)
    }

    "create a DocumentSetCreationJob" in new StartClusteringExistingDocumentSetScope {
      h.status(result)
      there was no(mockDocumentSetBackend).create(any, any)
      there was one(mockStorage).createMassUploadDocumentSetCreationJob(
        documentSetId, 234L, lang, false, stopWords, importantWords, false)
    }

    "send a ClusterFileGroup message" in new StartClusteringExistingDocumentSetScope {
      h.status(result)
      there was one(mockMessageQueue).startClustering(job.toDeprecatedDocumentSetCreationJob, "[add-to-existing-docset]")
    }

    "redirect if user has no FileGroup in progress" in new StartClusteringExistingDocumentSetScope {
      mockFileGroupBackend.find(user.email, None) returns Future.successful(None)
      h.status(result) must beEqualTo(h.SEE_OTHER)
      there was no(mockDocumentSetBackend).create(any, any)
    }
  }

  "#cancel" should {
    trait CancelScope extends BaseScope {
      mockFileGroupBackend.destroy(any) returns Future.successful(())
      val user = User(id=123L, email="cancel-user@example.org")

      val request = new AuthorizedRequest(FakeRequest(), Session(user.id, "127.0.0.1"), user)
      lazy val result = controller.cancel()(request)
    }

    "mark a file group as deleted" in new CancelScope {
      mockFileGroupBackend.find(user.email, None) returns Future.successful(Some(factory.fileGroup(id=234L)))
      h.status(result)
      there was one(mockFileGroupBackend).destroy(234L)
    }

    "do nothing when the file group does not exist" in new CancelScope {
      mockFileGroupBackend.find(user.email, None) returns Future.successful(None)
      h.status(result)
      there was no(mockFileGroupBackend).destroy(any)
    }

    "return Accepted" in new CancelScope {
      mockFileGroupBackend.find(user.email, None) returns Future.successful(None)
      h.status(result) must beEqualTo(h.ACCEPTED)
    }
  }
}
