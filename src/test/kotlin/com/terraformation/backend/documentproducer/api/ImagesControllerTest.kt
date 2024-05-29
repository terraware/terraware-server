package com.terraformation.pdd.variable.api

import com.terraformation.pdd.ControllerIntegrationTest
import com.terraformation.pdd.file.InMemoryFileStore
import com.terraformation.pdd.jooq.VariableType
import com.terraformation.pdd.jooq.VariableValueId
import com.terraformation.pdd.jooq.tables.references.VARIABLE_VALUES
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart

class ImagesControllerTest : ControllerIntegrationTest() {
  override val tablesToResetSequences = listOf(VARIABLE_VALUES)

  @Autowired private lateinit var fileStore: InMemoryFileStore

  @Nested
  inner class GetImageValue {
    @Test
    fun `can get full-sized image file`() {
      val imageVariableId = insertVariableManifestEntry(insertVariable(type = VariableType.Image))

      val uri = URI("https://test")
      val contents = byteArrayOf(1, 2, 3, 4)
      val fileId = insertFile(size = contents.size, storageUrl = uri)
      fileStore.write(uri, contents.inputStream())

      val imageValueId = insertImageValue(imageVariableId, fileId)

      mockMvc.get("/api/v1/pdds/$cannedDocumentId/images/$imageValueId").andExpect {
        status { isOk() }
        content { bytes(contents) }
      }
    }

    @Test
    fun `gets thumbnail if dimensions are specified`() {
      val imageVariableId = insertVariableManifestEntry(insertVariable(type = VariableType.Image))

      val uri = URI("https://thumb")
      val contents = byteArrayOf(1, 2, 3, 4)
      val fileId = insertFile()
      insertThumbnail(fileId = fileId, size = contents.size, storageUrl = uri)
      fileStore.write(uri, contents.inputStream())

      val imageValueId = insertImageValue(imageVariableId, fileId)

      mockMvc.get("/api/v1/pdds/$cannedDocumentId/images/$imageValueId?maxWidth=320").andExpect {
        status { isOk() }
        content { bytes(contents) }
      }
    }

    @Test
    fun `image value must be from correct document`() {
      val imageVariableId = insertVariableManifestEntry(insertVariable(type = VariableType.Image))
      val otherDocumentId = insertDocument()

      val uri = URI("https://test")
      val contents = byteArrayOf(1, 2, 3, 4)
      val fileId = insertFile(size = contents.size, storageUrl = uri)
      fileStore.write(uri, contents.inputStream())

      val imageValueId = insertImageValue(imageVariableId, fileId)

      mockMvc.get("/api/v1/pdds/$otherDocumentId/images/$imageValueId").andExpect {
        status { isNotFound() }
      }
    }

    @Test
    fun `cannot get nonexistent image`() {
      mockMvc.get("/api/v1/pdds/$cannedDocumentId/images/1").andExpect { status { isNotFound() } }
    }
  }

  @Nested
  inner class UploadImageValue {
    @Test
    fun `uploaded image is stored`() {
      val imageVariableId = insertVariableManifestEntry(insertVariable(type = VariableType.Image))

      val caption = "Image caption"
      val fileData = byteArrayOf(1, 2, 3, 4)
      val filename = "test.jpg"

      mockMvc
          .multipart(HttpMethod.POST, "/api/v1/pdds/$cannedDocumentId/images") {
            file(MockMultipartFile("file", filename, MediaType.IMAGE_JPEG_VALUE, fileData))
            part(MockPart("caption", caption.toByteArray()))
            part(MockPart("variableId", "$imageVariableId".toByteArray()))
            part(MockPart("listPosition", "0".toByteArray()))
          }
          .andExpectJson(
              """
                {
                  "valueId": 1,
                  "status": "ok"
                }
              """
                  .trimIndent())

      val imageValuesRow = variableImageValuesDao.findAll().single()
      assertEquals(caption, imageValuesRow.caption, "Caption")

      val filesRow = filesDao.findAll().single()
      assertEquals(
          listOf(filename, "image/jpeg"),
          listOf(filesRow.fileName, filesRow.contentType),
          "File metadata")

      assertArrayEquals(fileData, fileStore.files.values.single())
    }

    @Test
    fun `can store multiple images in table cell`() {
      val tableVariableId = insertVariableManifestEntry(insertTableVariable())
      val imageVariableId = insertVariableManifestEntry(insertVariable(type = VariableType.Image))
      insertTableColumn(tableVariableId, imageVariableId)

      val rowValueId = insertValue(variableId = tableVariableId)
      insertImageValue(imageVariableId, insertFile(), listPosition = 0)

      val caption = "Image caption"
      val fileData = byteArrayOf(1, 2, 3, 4)
      val filename = "test.jpg"
      val listPosition = 1

      mockMvc
          .multipart(HttpMethod.POST, "/api/v1/pdds/$cannedDocumentId/images") {
            file(MockMultipartFile("file", filename, MediaType.IMAGE_JPEG_VALUE, fileData))
            part(MockPart("caption", caption.toByteArray()))
            part(MockPart("variableId", "$imageVariableId".toByteArray()))
            part(MockPart("listPosition", "$listPosition".toByteArray()))
            part(MockPart("rowValueId", "$rowValueId".toByteArray()))
          }
          .andExpectJson(
              """
                {
                  "valueId": 3,
                  "status": "ok"
                }
              """
                  .trimIndent())

      val valuesRow = variableValuesDao.fetchOneById(VariableValueId(3))!!
      assertEquals(1, valuesRow.listPosition, "New value should be at list position 1")

      val tableRowRows = variableValueTableRowsDao.fetchByVariableValueId(VariableValueId(3))
      assertEquals(
          listOf(rowValueId),
          tableRowRows.map { it.tableRowValueId },
          "New image value should be in table row")
    }

    @Test
    fun `allocates next available list position if not specified`() {
      val imageVariableId =
          insertVariableManifestEntry(insertVariable(type = VariableType.Image, isList = true))
      insertImageValue(imageVariableId, insertFile(), listPosition = 0)
      insertImageValue(imageVariableId, insertFile(), listPosition = 1)

      val caption = "Image caption"
      val fileData = byteArrayOf(1, 2, 3, 4)
      val filename = "test.jpg"

      mockMvc
          .multipart(HttpMethod.POST, "/api/v1/pdds/$cannedDocumentId/images") {
            file(MockMultipartFile("file", filename, MediaType.IMAGE_JPEG_VALUE, fileData))
            part(MockPart("caption", caption.toByteArray()))
            part(MockPart("variableId", "$imageVariableId".toByteArray()))
          }
          .andExpectJson(
              """
                {
                  "valueId": 3,
                  "status": "ok"
                }
              """
                  .trimIndent())

      val valuesRow = variableValuesDao.fetchOneById(VariableValueId(3))!!
      assertEquals(2, valuesRow.listPosition, "List position of new value")
    }

    @Test
    fun `returns error if variable is not an image`() {
      val textVariableId = insertVariableManifestEntry(insertTextVariable())

      mockMvc
          .multipart(HttpMethod.POST, "/api/v1/pdds/$cannedDocumentId/images") {
            file(MockMultipartFile("file", "dummy", MediaType.IMAGE_JPEG_VALUE, byteArrayOf(1)))
            part(MockPart("caption", "dummy".toByteArray()))
            part(MockPart("variableId", "$textVariableId".toByteArray()))
            part(MockPart("listPosition", "0".toByteArray()))
          }
          .andExpect { status { isConflict() } }
    }
  }
}
