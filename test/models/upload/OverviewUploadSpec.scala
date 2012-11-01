package models.upload

import helpers.PgConnectionContext
import java.sql.Timestamp
import java.util.UUID._
import models.OverviewUser
import org.specs2.mutable.Specification
import play.api.Play.{start, stop}
import play.api.test.FakeApplication

class OverviewUploadSpec extends Specification {

  step(start(FakeApplication()))
  
  "OverviewUpload" should {

    trait UploadContext extends PgConnectionContext {
      val uuid = randomUUID
      val filename = "file"
      val totalSize = 42l
      val chunk: Array[Byte] = Array(0x12, 0x13, 0x14)
      var user: OverviewUser = _

      override def setupWithDb = user = OverviewUser.findById(1).get
    }
    
    "be created with 0 size uploaded" in new UploadContext {
      LO.withLargeObject { lo =>
	val before = new Timestamp(System.currentTimeMillis)
	val upload = OverviewUpload(user, uuid, filename, totalSize, lo.oid)

	upload.lastActivity.compareTo(before) must beGreaterThanOrEqualTo(0)
	upload.bytesUploaded must be equalTo(0)
	upload.contentsOid must be equalTo(lo.oid)
      }
    }

    "update bytesUploaded" in new UploadContext {
      LO.withLargeObject { lo =>
	val before = new Timestamp(System.currentTimeMillis)
	val upload = OverviewUpload(user, uuid, filename, totalSize, lo.oid)

	val uploadedSize = lo.add(chunk)

	val updateTime = new Timestamp(System.currentTimeMillis)
	val updatedUpload = upload.withUploadedBytes(uploadedSize)
	
	updatedUpload.lastActivity.compareTo(updateTime) must beGreaterThanOrEqualTo(0)
	updatedUpload.bytesUploaded must be equalTo(uploadedSize)
      }
    }
    
  }

  step(stop)
}
