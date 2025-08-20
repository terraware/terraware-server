package com.terraformation.backend.documentproducer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.db.variable.VariableImporter
import com.terraformation.backend.documentproducer.event.VariablesUpdatedEvent
import com.terraformation.backend.documentproducer.event.VariablesUploadedEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import io.mockk.mockk
import org.junit.jupiter.api.Test

class VariableServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  private val systemUser = SystemUser(mockk())
  private val messages = Messages()
  private val eventPublisher = TestEventPublisher()

  private val variableStore: VariableStore by lazy {
    VariableStore(
        dslContext,
        variableNumbersDao,
        variablesDao,
        variableSectionDefaultValuesDao,
        variableSectionRecommendationsDao,
        variableSectionsDao,
        variableSelectsDao,
        variableSelectOptionsDao,
        variableTablesDao,
        variableTableColumnsDao,
        variableTextsDao,
    )
  }

  private val variableImporter: VariableImporter by lazy {
    VariableImporter(DeliverableStore(dslContext), dslContext, messages, variableStore)
  }

  private val service: VariableService by lazy {
    VariableService(
        dslContext,
        systemUser,
        variableImporter,
        variableStore,
        VariableValueStore(
            TestClock(),
            dslContext,
            eventPublisher,
            variableImageValuesDao,
            variableLinkValuesDao,
            variablesDao,
            variableSectionValuesDao,
            variableSelectOptionValuesDao,
            variableValuesDao,
            variableValueTableRowsDao,
        ),
        eventPublisher,
    )
  }

  @Test
  fun `test event listener when variables are updated`() {
    assertIsEventListener<VariablesUpdatedEvent>(service)
  }

  @Test
  fun `test importAllVariables publishes event`() {
    service.importAllVariables("".byteInputStream())

    eventPublisher.assertEventPublished { event -> event is VariablesUploadedEvent }
  }
}
