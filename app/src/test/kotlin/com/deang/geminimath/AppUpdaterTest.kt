package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
  @Test fun comparesVersions() {
    assertTrue(AppUpdater.isNewer("v1.0.1", "1.0.0"))
    assertTrue(AppUpdater.isNewer("1.10.0", "1.9.3"))
    assertTrue(AppUpdater.isNewer("v2", "1.9.9"))
    assertFalse(AppUpdater.isNewer("v1.0.0", "1.0.0"))
    assertFalse(AppUpdater.isNewer("1.0", "1.0.0"))
    assertFalse(AppUpdater.isNewer("v0.9.0", "1.0.0"))
    assertFalse(AppUpdater.isNewer("v1.0.0-beta", "1.0.0"))
  }
}
