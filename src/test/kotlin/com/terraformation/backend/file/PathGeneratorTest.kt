package com.terraformation.backend.file

import java.time.Instant
import kotlin.io.path.Path
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PathGeneratorTest {
  private val timestamp = Instant.parse("2024-03-15T12:34:56Z")
  private val pathGenerator = PathGenerator(Random(0))

  /** Hexadecimal form of the first value from the generator's seeded random number source. */
  private val randomValue = "8CB4C22C53FEAE50"

  @CsvSource(
      "content type extension     , image/jpeg              ,                 , jpg",
      "content type beats filename, image/jpeg              , photo.png       , jpg",
      "filename extension         , application/json+frames , frames.jsonl    , jsonl",
      "uppercase filename         , application/x-bogus     , DATA.JSON       , json",
      "no filename                , application/json+frames ,                 , bin",
      "filename with multiple dots, application/json+frames , foo.bar.jsonx   , jsonx",
      "filename without extension , application/x-bogus     , frames          , bin",
      "unusable filename extension, application/x-bogus     , frames.j/../son , bin",
      ignoreLeadingAndTrailingWhitespace = true,
  )
  @ParameterizedTest(name = "{0}")
  fun `uses extension of content type, falling back to extension of filename`(
      name: String,
      contentType: String,
      filename: String?,
      expectedExtension: String,
  ) {
    val path = pathGenerator.generatePath(timestamp, "category", contentType, filename)

    assertEquals(Path("2024/03/15/category/123456-$randomValue.$expectedExtension"), path)
  }
}
