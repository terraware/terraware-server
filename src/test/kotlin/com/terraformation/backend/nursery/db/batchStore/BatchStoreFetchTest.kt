package com.terraformation.backend.nursery.db.batchStore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreFetchTest : BatchStoreTest() {
  @Test
  fun `returns database row`() {
    val batchId = insertBatch(speciesId = speciesId)

    val expected = batchesDao.fetchOneById(batchId)
    val actual = store.fetchOneById(batchId)

    assertEquals(expected, actual)
  }
}
