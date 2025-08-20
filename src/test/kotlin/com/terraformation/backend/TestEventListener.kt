package com.terraformation.backend

import org.junit.jupiter.api.Assertions.assertNotNull
import org.springframework.context.event.EventListener

inline fun <reified T> assertIsEventListener(obj: Any, method: String = "on") {
  assertNotNull(
      obj.javaClass.getMethod(method, T::class.java).getAnnotation(EventListener::class.java),
      "$method(${T::class.simpleName}) missing EventListener annotation",
  )
}
