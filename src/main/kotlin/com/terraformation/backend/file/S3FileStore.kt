package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.annotation.ManagedBean
import javax.annotation.Priority
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@ConditionalOnProperty("terraware.photo-bucket-name", havingValue = "")
@ManagedBean
@Priority(1) // If both S3 and filesystem storage are configured, prefer S3.
class S3FileStore(config: TerrawareServerConfig) : FileStore {
  private val log = perClassLogger()
  private val s3Client = S3Client.create()!!

  // Exception here should be impossible thanks to @ConditionalOnProperty
  private val bucketName =
      config.s3BucketName
          ?: throw IllegalArgumentException("No S3 bucket name found in configuration")

  override fun delete(path: Path) {
    // Test whether the file exists; S3's delete endpoint doesn't return "not found" responses.
    size(path)

    val s3Key = toS3Key(path)

    log.info("Deleting $s3Key from $bucketName")

    val request = DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build()

    mapExceptions(path) {
      val response = s3Client.deleteObject(request)
      log.info(
          "Response: delete marker = ${response.deleteMarker()}, version = ${response.versionId()}")
    }
  }

  override fun read(path: Path): SizedInputStream {
    val request = GetObjectRequest.builder().bucket(bucketName).key(toS3Key(path)).build()

    return mapExceptions(path) {
      val responseInputStream = s3Client.getObject(request)
      SizedInputStream(responseInputStream, responseInputStream.response().contentLength())
    }
  }

  override fun size(path: Path): Long {
    val request = HeadObjectRequest.builder().bucket(bucketName).key(toS3Key(path)).build()

    return mapExceptions(path) {
      val response = s3Client.headObject(request)
      response.contentLength()
    }
  }

  override fun write(path: Path, contents: InputStream, size: Long) {
    val s3Key = toS3Key(path)

    // Check whether the file already exists so that we can avoid overwriting it. There is a race
    // condition here if the same path is written twice in parallel. S3 does not support atomic
    // "create if not exists" functionality, so avoiding the race condition would require some
    // kind of synchronization mechanism like grabbing locks. We expect the race to be too rare to
    // justify the cost of such a mechanism.
    try {
      size(path)
      throw FileAlreadyExistsException(s3Key)
    } catch (e: NoSuchFileException) {
      // This is the happy path; we expect the file to not exist yet.
    }

    log.info("Writing $s3Key to $bucketName")

    val request = PutObjectRequest.builder().bucket(bucketName).key(s3Key).build()

    mapExceptions(path) {
      log.debugWithTiming("Wrote $size bytes to S3") {
        s3Client.putObject(request, RequestBody.fromInputStream(contents, size))
      }
    }
  }

  /**
   * Returns an S3 key for a path. S3 keys always use `/` as the path separator and are always
   * relative (never start with `/`).
   */
  private fun toS3Key(path: Path): String {
    val relativePath = if (path.isAbsolute) path.relativeTo(path.root) else path
    return relativePath.invariantSeparatorsPathString
  }

  /**
   * Runs a block of code and translates AWS-specific exceptions to more generic equivalents so
   * calling code doesn't have to be aware of which kind of photo content store it's using.
   */
  private fun <T> mapExceptions(relativePath: Path, func: () -> T): T {
    return try {
      func()
    } catch (e: NoSuchKeyException) {
      throw NoSuchFileException(toS3Key(relativePath))
    } catch (e: NoSuchBucketException) {
      log.error("S3 bucket $bucketName not found")
      throw FileSystemException("Cannot access S3 bucket")
    }
  }
}
