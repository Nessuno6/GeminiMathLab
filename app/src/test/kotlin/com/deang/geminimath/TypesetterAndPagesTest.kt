package com.deang.geminimath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypesetterAndPagesTest {

  @Test fun mathTypesetterParsesFractionsRadicalsMatricesAndBigOps() {
    val frac = MathTypesetter.parse("\\frac{x+1}{2}")
    assertTrue("Expected Frac node, got $frac", frac is MathTypesetter.MathNode.Frac)

    val rad = MathTypesetter.parse("√(x² + 1)")
    assertTrue("Expected Sqrt node, got $rad", rad is MathTypesetter.MathNode.Sqrt)

    val mat = MathTypesetter.parse("[1, 2; 3, 4]")
    assertTrue("Expected Matrix node, got $mat", mat is MathTypesetter.MathNode.Matrix)
    assertEquals(2, (mat as MathTypesetter.MathNode.Matrix).rows.size)
    assertEquals(2, mat.rows[0].size)

    val intOp = MathTypesetter.parse("∫ from 0 to 1 of x^2")
    assertTrue("Expected Row containing BigOp, got $intOp", intOp is MathTypesetter.MathNode.Row)
    val children = (intOp as MathTypesetter.MathNode.Row).children
    assertTrue(children.any { it is MathTypesetter.MathNode.BigOp && it.symbol == "∫" })
  }

  @Test fun backgroundPagesPathHelpersRoundTrip() {
    assertEquals(emptyList<String>(), BackgroundPages.paths(null))
    assertEquals(emptyList<String>(), BackgroundPages.paths("  ; ; "))

    val joined = BackgroundPages.join(listOf("/a/p1.png", " ", "/a/p2.png"))
    assertEquals("/a/p1.png;/a/p2.png", joined)
    assertEquals(listOf("/a/p1.png", "/a/p2.png"), BackgroundPages.paths(joined))
    assertEquals("/a/p1.png", BackgroundPages.firstPath(joined))
    assertNull(BackgroundPages.join(emptyList()))
  }

  @Test fun lazyNoteThumbKeyUsesStrokeCountHint() {
    val lazyNote = MathNote(
      id = "n1",
      title = "Lazy",
      updatedAt = 12345L,
      paperPattern = PaperPattern.GRID,
      graphColor = GraphColorTheme.EMERALD,
      showInlinePlotter = false,
      recognizedLatex = "x^2",
      strokes = emptyList(),
      strokesLoaded = false,
      strokeCountHint = 17
    )
    assertEquals(17, lazyNote.strokeCount)
    assertEquals("n1:12345:17:100x100", NoteThumbCache.key(lazyNote, 100, 100))
  }
}
