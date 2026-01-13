package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

internal class AccessionStorePhotoTest : AccessionStoreTest() {
  @Test
  fun `photo filenames are returned`() {
    val initial = store.create(accessionModel())
    val filesRow =
        FilesRow(
            fileName = "photo.jpg",
            createdBy = user.userId,
            createdTime = Instant.now(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
            size = 123,
            storageUrl = URI("file:///photo.jpg"),
        )
    filesDao.insert(filesRow)

    accessionPhotosDao.insert(AccessionPhotosRow(accessionId = initial.id!!, fileId = filesRow.id))

    val fetched = store.fetchOneById(initial.id)

    assertEquals(listOf("photo.jpg"), fetched.photoFilenames)
  }
}
