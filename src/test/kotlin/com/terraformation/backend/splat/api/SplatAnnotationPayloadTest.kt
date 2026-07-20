package com.terraformation.backend.splat.api

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SplatAnnotationId
import com.terraformation.backend.splat.CoordinateModel
import com.terraformation.backend.splat.ExistingSplatAnnotationModel
import com.terraformation.backend.splat.SplatAnnotationMediaModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplatAnnotationPayloadTest {
  @Test
  fun `maps annotation media list into the payload preserving order`() {
    val model =
        ExistingSplatAnnotationModel(
            id = SplatAnnotationId(1),
            fileId = FileId(10),
            media =
                listOf(
                    SplatAnnotationMediaModel(FileId(20), "image/jpeg", 0),
                    SplatAnnotationMediaModel(FileId(21), "video/mp4", 1),
                ),
            position = CoordinateModel(1.0, 2.0, 3.0),
            title = "Annotation",
        )

    val payload = SplatAnnotationPayload.of(model)

    assertEquals(
        listOf(
            SplatAnnotationMediaPayload("image/jpeg", FileId(20), 0),
            SplatAnnotationMediaPayload("video/mp4", FileId(21), 1),
        ),
        payload.media,
    )
  }
}
