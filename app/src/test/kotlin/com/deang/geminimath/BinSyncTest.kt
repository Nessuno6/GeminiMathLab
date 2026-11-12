package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Test

class BinSyncTest {
  private val now = 100L * 24 * 3600 * 1000
  private fun day(n: Int) = now - n * 24L * 3600 * 1000
  private fun entry(id: String, deletedAt: Long, onDevice: Boolean) = NoteBin.Entry(id, "T", deletedAt, "", onDevice)
  private fun plan(
    local: Map<String, Long> = emptyMap(), bin: Map<String, NoteBin.Entry> = emptyMap(),
    purged: Set<String> = emptySet(), remote: List<BinSync.Remote> = emptyList()
  ) = BinSync.plan(local, bin, purged, remote, now)

  @Test fun notesSyncBothWays() {
    assertEquals(listOf(BinSync.Action.Upload("a", "f")), plan(local = mapOf("a" to day(1)), remote = listOf(BinSync.Remote("a", "f", null, null))))
    assertEquals(listOf(BinSync.Action.Upload("a", null)), plan(local = mapOf("a" to day(1))))
    assertEquals(listOf(BinSync.Action.Download("b", "f")), plan(remote = listOf(BinSync.Remote("b", "f", null, null))))
  }

  @Test fun deletingHereMarksItDeletedOnDrive() {
    // A note deleted on this device doesn't come back from Drive: Drive's copy goes to the bin
    val bin = mapOf("a" to entry("a", day(1), onDevice = true))
    assertEquals(listOf(BinSync.Action.MarkDeleted("a", "f")), plan(bin = bin, remote = listOf(BinSync.Remote("a", "f", null, null))))
    assertEquals(listOf(BinSync.Action.MarkDeleted("a", null)), plan(bin = bin))
  }

  @Test fun deletedOnAnotherDeviceGoesToThisBin() {
    val r = listOf(BinSync.Remote("a", "f", day(1), "Algebra"))
    assertEquals(listOf(BinSync.Action.BinHere("a", day(1))), plan(local = mapOf("a" to day(3)), remote = r))
    assertEquals(listOf(BinSync.Action.AddToBin("a", "Algebra", day(1))), plan(remote = r))
  }

  @Test fun editedOrRestoredAfterDeletionWins() {
    val r = listOf(BinSync.Remote("a", "f", day(5), null))
    assertEquals(listOf(BinSync.Action.Undelete("a", "f")), plan(local = mapOf("a" to day(1)), remote = r))
  }

  @Test fun restoredOnAnotherDeviceComesBack() {
    val bin = mapOf("a" to entry("a", day(4), onDevice = false))
    assertEquals(listOf(BinSync.Action.Restore("a", "f")), plan(bin = bin, remote = listOf(BinSync.Remote("a", "f", null, null))))
  }

  @Test fun afterThirtyDaysItsGoneEverywhere() {
    val bin = mapOf("a" to entry("a", day(31), onDevice = false))
    assertEquals(
      listOf(BinSync.Action.DeleteRemote("a", "f"), BinSync.Action.DropFromBin("a")),
      plan(bin = bin, remote = listOf(BinSync.Remote("a", "f", day(31), null)))
    )
  }

  @Test fun deleteForever() {
    assertEquals(listOf(BinSync.Action.DeleteRemote("a", "f")), plan(purged = setOf("a"), remote = listOf(BinSync.Remote("a", "f", day(2), null))))
    // Deleted forever on another device: the placeholder here goes
    assertEquals(listOf(BinSync.Action.DropFromBin("a")), plan(bin = mapOf("a" to entry("a", day(2), onDevice = false))))
  }

  @Test fun binnedHereAndOnDriveBecomesPlaceholder() {
    val bin = mapOf("a" to entry("a", day(2), onDevice = true))
    assertEquals(listOf(BinSync.Action.Stub("a")), plan(bin = bin, remote = listOf(BinSync.Remote("a", "f", day(2), null))))
    // Already a placeholder: nothing to do
    assertEquals(emptyList<BinSync.Action>(), plan(bin = mapOf("a" to entry("a", day(2), false)), remote = listOf(BinSync.Remote("a", "f", day(2), null))))
  }
}
