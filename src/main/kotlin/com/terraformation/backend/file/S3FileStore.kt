package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import jakarta.annotation.Priority
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.UploadPartRequest

@ConditionalOnProperty("terraware.s3BucketName", havingValue = "")
@Named
@Priority(10) // If both S3 and filesystem storage are configured, prefer S3.
class S3FileStore(config: TerrawareServerConfig, private val pathGenerator: PathGenerator) :
    FileStore {
  private val log = perClassLogger()
  private val s3Client = S3Client.create()!!

  // Exception here should be impossible thanks to @ConditionalOnProperty
  private val bucketName =
      config.s3BucketName
          ?: throw IllegalArgumentException("No S3 bucket name found in configuration")

  override fun delete(url: URI) {
    // Test whether the file exists; S3's delete endpoint doesn't return "not found" responses.
    size(url)

    val s3Key = toS3Key(url)

    log.info("Deleting $s3Key from $bucketName")

    val request = DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build()

    mapExceptions(url) {
      val response = s3Client.deleteObject(request)
      log.info(
          "Response: delete marker = ${response.deleteMarker()}, version = ${response.versionId()}"
      )
    }
  }

  override fun read(url: URI): SizedInputStream {
    val request = GetObjectRequest.builder().bucket(bucketName).key(toS3Key(url)).build()

    return mapExceptions(url) {
      val responseInputStream = s3Client.getObject(request)
      SizedInputStream(responseInputStream, responseInputStream.response().contentLength())
    }
  }

  override fun size(url: URI): Long {
    val request = HeadObjectRequest.builder().bucket(bucketName).key(toS3Key(url)).build()

    return mapExceptions(url) {
      val response = s3Client.headObject(request)
      response.contentLength()
    }
  }

  override fun write(url: URI, contents: InputStream) {
    val s3Key = toS3Key(url)

    // Check whether the file already exists so that we can avoid overwriting it. There is a race
    // condition here if the same path is written twice in parallel. S3 does not support atomic
    // "create if not exists" functionality, so avoiding the race condition would require some
    // kind of synchronization mechanism like grabbing locks. We expect the race to be too rare to
    // justify the cost of such a mechanism.
    try {
      size(url)
      throw FileAlreadyExistsException(s3Key)
    } catch (e: NoSuchFileException) {
      // This is the happy path; we expect the file to not exist yet.
    }

    log.info("Writing $s3Key to $bucketName")

    mapExceptions(url) {
      val createUploadRequest =
          CreateMultipartUploadRequest.builder().bucket(bucketName).key(s3Key).build()
      val uploadId = s3Client.createMultipartUpload(createUploadRequest).uploadId()

      try {
        val completedParts =
            readChunks(contents)
                .mapIndexed { index, chunk ->
                  log.debug("Writing chunk of ${chunk.size} bytes")

                  // Part numbers must be greater than 0
                  val partNumber = index + 1

                  val partRequest =
                      UploadPartRequest.builder()
                          .bucket(bucketName)
                          .contentLength(chunk.size.toLong())
                          .key(s3Key)
                          .partNumber(partNumber)
                          .uploadId(uploadId)
                          .build()

                  val response = s3Client.uploadPart(partRequest, RequestBody.fromBytes(chunk))

                  CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build()
                }
                .toList()

        val completedMultipartUpload =
            CompletedMultipartUpload.builder().parts(completedParts).build()
        val completeMultipartUploadRequest =
            CompleteMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .multipartUpload(completedMultipartUpload)
                .uploadId(uploadId)
                .build()
        s3Client.completeMultipartUpload(completeMultipartUploadRequest)
      } catch (e: Exception) {
        val abortMultipartUploadRequest =
            AbortMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .uploadId(uploadId)
                .build()
        s3Client.abortMultipartUpload(abortMultipartUploadRequest)
        throw e
      }
    }
  }

  override fun canAccept(url: URI): Boolean {
    return url.scheme == "s3" && url.host == bucketName && url.path.length > 1 && url.path[0] == '/'
  }

  override fun getUrl(path: Path): URI {
    val relativePath = if (path.isAbsolute) path.relativeTo(path.root) else path
    return URI("s3://$bucketName/${relativePath.invariantSeparatorsPathString}")
  }

  override fun newUrl(timestamp: Instant, category: String, contentType: String): URI {
    return getUrl(pathGenerator.generatePath(timestamp, category, contentType))
  }

  /**
   * Returns a sequence of chunks from an input stream.
   *
   * @param [chunkSize] How many bytes to read into each chunk. Note that AWS has a minimum size of
   *   5MB for each part of a multipart upload except the final one; this needs to be bigger than
   *   that.
   */
  private fun readChunks(
      inputStream: InputStream,
      chunkSize: Int = 5 * 1024 * 1024,
  ): Sequence<ByteArray> = sequence {
    while (true) {
      val chunk = inputStream.readNBytes(chunkSize)
      if (chunk.isEmpty()) {
        break
      }

      yield(chunk)
    }
  }

  /** Returns an S3 key for a URL. */
  private fun toS3Key(url: URI): String {
    if (!canAccept(url)) {
      throw InvalidStorageLocationException(url)
    }

    return url.path.substring(1)
  }

  /**
   * Runs a block of code and translates AWS-specific exceptions to more generic equivalents so
   * calling code doesn't have to be aware of which kind of photo content store it's using.
   */
  private fun <T> mapExceptions(url: URI, func: () -> T): T {
    return try {
      func()
    } catch (e: NoSuchKeyException) {
      throw NoSuchFileException(toS3Key(url))
    } catch (e: NoSuchBucketException) {
      log.error("S3 bucket $bucketName not found")
      throw FileSystemException("Cannot access S3 bucket")
    }
  }
}
