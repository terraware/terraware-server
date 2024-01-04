package com.terraformation.backend.admin

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UserId
import org.springframework.beans.propertyeditors.StringTrimmerEditor
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.InitBinder

@ControllerAdvice("com.terraformation.backend.admin")
class AdminControllerAdvice {
  /**
   * Configure the controllers to interpret empty strings as null values for any data types that can
   * be entered by the user in a form. This is not needed for ID wrapper classes that are always
   * treated as mandatory inputs.
   */
  @InitBinder
  fun treatEmptyStringFormParamsAsNull(binder: WebDataBinder) {
    val stringTrimmerEditor = StringTrimmerEditor(true)

    listOf(
            String::class.java,
            FacilityId::class.java,
            UserId::class.java,
        )
        .forEach { clazz -> binder.registerCustomEditor(clazz, stringTrimmerEditor) }
  }
}
