package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.getEnvOrSkipTest
import com.terraformation.backend.log.perClassLogger
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.nio.file.NoSuchFileException
import kotlin.random.Random
import org.junit.Assume.assumeNoException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/**
 * Tests for S3 file storage. Since this interacts with a real S3 bucket, by default these tests are
 * skipped. To run them:
 * 1. Make sure your AWS credentials are configured, e.g., in `$HOME/.aws/config`.
 * 2. Create an S3 bucket, e.g., by running `aws s3 mb s3://my-test-bucket`.
 * 3. Set the `TEST_S3_BUCKET_NAME` environment variable to the name of your bucket.
 *
 * If `TEST_S3_BUCKET_NAME` is set and there are valid AWS credentials available, these tests will
 * run.
 */
internal class S3FileStoreExternalTest : FileStoreTest() {
  private val bucketName = getEnvOrSkipTest("TEST_S3_BUCKET_NAME")

  private val config: TerrawareServerConfig = mockk()
  private val log = perClassLogger()

  private lateinit var s3Client: S3Client
  override lateinit var store: FileStore

  // Keep track of what we're trying to create in the bucket so that we can clean up afterwards.
  private val keysCreated = mutableListOf<String>()

  @BeforeEach
  fun setUp() {
    try {
      s3Client = S3Client.create()
    } catch (e: Exception) {
      assumeNoException(e)
    }

    every { config.s3BucketName } returns bucketName

    store = S3FileStore(config, PathGenerator())
  }

  @AfterEach
  fun deleteTestFiles() {
    keysCreated.forEach { key ->
      try {
        log.debug("Deleting $key from $bucketName")
        s3Client.deleteObject(DeleteObjectRequest.builder().key(key).bucket(bucketName).build())
      } catch (ignore: Exception) {
        // Swallow exceptions so the test doesn't fail if we can't clean up
      }
    }
  }

  override fun makeUrl(): URI {
    val url = URI("s3://$bucketName/${Random.nextInt()}")
    keysCreated.add(url.path.substring(1))
    return url
  }

  override fun createFile(url: URI, content: ByteArray) {
    s3Client.putObject(
        PutObjectRequest.builder().bucket(bucketName).key(url.path.substring(1)).build(),
        RequestBody.fromBytes(content),
    )
  }

  override fun fileExists(url: URI): Boolean {
    return try {
      readFile(url)
      true
    } catch (e: NoSuchFileException) {
      false
    }
  }

  override fun readFile(url: URI): ByteArray {
    val key = url.path.substring(1)

    return try {
      s3Client
          .getObject(
              GetObjectRequest.builder().bucket(bucketName).key(key).build(),
              ResponseTransformer.toBytes(),
          )
          .asByteArray()
    } catch (e: NoSuchKeyException) {
      throw NoSuchFileException(key)
    }
  }
}
