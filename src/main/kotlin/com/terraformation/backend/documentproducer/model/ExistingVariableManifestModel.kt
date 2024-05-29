package com.terraformation.pdd.variable.model

import com.terraformation.pdd.jooq.MethodologyId
import com.terraformation.pdd.jooq.UserId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.jooq.tables.pojos.VariableManifestsRow
import java.time.Instant

data class ExistingVariableManifestModel(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: VariableManifestId,
    val methodologyId: MethodologyId,
) {
  constructor(
      row: VariableManifestsRow,
  ) : this(
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      id = row.id!!,
      methodologyId = row.methodologyId!!,
  )
}
