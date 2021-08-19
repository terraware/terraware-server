package com.terraformation.backend.file

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import io.mockk.every
import io.mockk.mockk
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeNotNull
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
 *
 * 1. Make sure your AWS credentials are configured, e.g., in `$HOME/.aws/config`.
 * 2. Create an S3 bucket, e.g., by running `aws s3 mb s3://my-test-bucket`.
 * 3. Set the `TEST_S3_BUCKET_NAME` environment variable to the name of your bucket.
 *
 * If `TEST_S3_BUCKET_NAME` is set and there are valid AWS credentials available, these tests will
 * run.
 */
internal class S3FileStoreTest : FileStoreTest() {
  private val bucketName = System.getenv("TEST_S3_BUCKET_NAME")

  private val config: TerrawareServerConfig = mockk()
  private val log = perClassLogger()

  private lateinit var s3Client: S3Client
  override lateinit var store: FileStore

  // Keep track of what we're trying to create in the bucket so that we can clean up afterwards.
  private val keysCreated = mutableListOf<String>()

  @BeforeEach
  fun setUp() {
    assumeNotNull(bucketName, "TEST_S3_BUCKET_NAME not set; skipping test")

    try {
      s3Client = S3Client.create()
    } catch (e: Exception) {
      assumeNoException(e)
    }

    every { config.s3BucketName } returns bucketName

    store = S3FileStore(config)
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

  override fun makePath(): Path {
    val path = super.makePath()
    keysCreated.add(path.invariantSeparatorsPathString)
    return path
  }

  override fun createFile(path: Path, content: ByteArray) {
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(bucketName)
            .key(path.invariantSeparatorsPathString)
            .build(),
        RequestBody.fromBytes(content))
  }

  override fun fileExists(path: Path): Boolean {
    return try {
      readFile(path)
      true
    } catch (e: NoSuchFileException) {
      false
    }
  }

  override fun readFile(path: Path): ByteArray {
    return try {
      s3Client
          .getObject(
              GetObjectRequest.builder()
                  .bucket(bucketName)
                  .key(path.invariantSeparatorsPathString)
                  .build(),
              ResponseTransformer.toBytes())
          .asByteArray()
    } catch (e: NoSuchKeyException) {
      throw NoSuchFileException(path.invariantSeparatorsPathString)
    }
  }
}
