package com.deang.geminimath

import kotlin.math.abs
import kotlin.math.max

/**
 * A numerical study of a function of x, as in a school "studio di funzione": domain, zeros,
 * y-intercept, maxima and minima, vertical and horizontal asymptotes and symmetry.
 * Works on any function the engine can evaluate (polynomials, roots, logs, trig, rational …).
 */
object FunctionStudy {
  data class Extremum(val x: Double, val y: Double, val isMax: Boolean)

  data class Result(
    /** Null: all real numbers. Otherwise e.g. "-2 ≤ x ≤ 2", "x > 0", "x ≠ 2". */
    val domain: String?,
    val zeros: List<Double>,
    val yIntercept: Double?,
    val extrema: List<Extremum>,
    val verticalAsymptotes: List<Double>,
    /** y = L as x → +∞ and as x → −∞ (null where there's none). */
    val horizontalRight: Double?,
    val horizontalLeft: Double?,
    /** "even" or "odd", or null. */
    val symmetry: String?
  )

  private const val LO = -40.0
  private const val HI = 40.0
  private const val N = 4000

  private fun fmt(v: Double) = MathFormat.num(v)

  /** Tidies a numerically found point: 1.9999999998 → 2, 0.30000001 → 0.3. */
  fun snap(v: Double): Double {
    val r = Math.rint(v * 1e6) / 1e6
    return if (abs(r - v) < 1e-7) r else v
  }

  fun study(f: (Double) -> Double, df: ((Double) -> Double)?): Result {
    val step = (HI - LO) / N
    val xs = DoubleArray(N + 1) { LO + it * step }
    val ys = DoubleArray(N + 1) { f(xs[it]) }
    fun ok(i: Int) = ys[i].isFinite()

    // --- Domain: runs of x where f is defined; edges found by bisection
    data class Run(val a: Double, val b: Double, val openLeft: Boolean, val openRight: Boolean)
    fun edge(inside: Double, outside: Double): Double {
      var i = inside; var o = outside
      repeat(60) { val m = 0.5 * (i + o); if (f(m).isFinite()) i = m else o = m }
      return snap(i)
    }
    val runs = mutableListOf<Run>()
    var i = 0
    while (i <= N) {
      if (!ok(i)) { i++; continue }
      val start = i
      while (i <= N && ok(i)) i++
      val end = i - 1
      val a = if (start == 0) Double.NEGATIVE_INFINITY else edge(xs[start], xs[start - 1])
      val b = if (end == N) Double.POSITIVE_INFINITY else edge(xs[end], xs[end + 1])
      runs += Run(a, b, a.isFinite() && !f(a).isFinite(), b.isFinite() && !f(b).isFinite())
    }

    // --- Vertical asymptotes: |f| blows up at a point (inside the domain, or at its edge)
    val poles = mutableListOf<Double>()
    fun addPole(c: Double) { val s = snap(c); if (poles.none { abs(it - s) < 1e-4 }) poles += s }
    fun blowsUp(c: Double) = listOf(c - 1e-7, c + 1e-7).any { val v = f(it); !v.isFinite() || abs(v) > 1e5 }
    for (k in 1 until N) {
      if (!ok(k - 1) || !ok(k + 1)) continue
      val a = abs(ys[k - 1]); val b = abs(ys[k]); val c = abs(ys[k + 1])
      // A sharp spike in |f| between samples, or a sign change across huge values
      val spike = ok(k) && b > 1e3 && b >= a && b >= c
      val flip = ok(k) && ys[k] * ys[k + 1] < 0 && b > 50 && c > 50
      if (spike || flip) {
        var lo = xs[k - 1]; var hi = xs[k + 1]
        // Narrow down to where |f| is largest
        repeat(80) {
          val m1 = lo + (hi - lo) / 3; val m2 = hi - (hi - lo) / 3
          val v1 = f(m1).let { if (it.isFinite()) abs(it) else Double.MAX_VALUE }
          val v2 = f(m2).let { if (it.isFinite()) abs(it) else Double.MAX_VALUE }
          if (v1 < v2) lo = m1 else hi = m2
        }
        val cnd = 0.5 * (lo + hi)
        if (blowsUp(cnd)) addPole(cnd)
      }
    }
    // Single undefined samples inside the domain (like x = 2 for 1/(x − 2), if the grid hits it)
    for (k in 1 until N) if (!ok(k) && ok(k - 1) && ok(k + 1) && blowsUp(xs[k])) addPole(xs[k])
    // Domain edges where f runs off to infinity, even slowly (ln x near 0 is only −28 at 10⁻¹²):
    // the closer to the edge, the bigger |f| keeps getting
    fun runsOff(edge: Double, dir: Int): Boolean {
      val near = f(edge + dir * 1e-12); val far = f(edge + dir * 1e-6)
      if (!near.isFinite()) return far.isFinite()
      return abs(near) > 1e5 || (abs(near) > 10 && abs(near) > 1.5 * abs(far) + 1)
    }
    for (r in runs) {
      if (r.a.isFinite() && runsOff(r.a, 1)) addPole(r.a)
      if (r.b.isFinite() && runsOff(r.b, -1)) addPole(r.b)
    }
    poles.sort()

    // --- Domain text
    val whole = runs.size == 1 && runs[0].a == Double.NEGATIVE_INFINITY && runs[0].b == Double.POSITIVE_INFINITY
    // Runs split only by poles (1/(x − 2)): "x ≠ 2"
    val onlyPoles = runs.isNotEmpty() && runs.first().a == Double.NEGATIVE_INFINITY && runs.last().b == Double.POSITIVE_INFINITY &&
      runs.zipWithNext().all { (p, q) -> abs(p.b - q.a) < 1e-3 && poles.any { abs(it - p.b) < 1e-3 } }
    val domain = when {
      runs.isEmpty() -> "none (f isn't defined for any real x)"
      whole && poles.isEmpty() -> null
      whole || onlyPoles -> poles.joinToString(", ") { "x ≠ ${fmt(it)}" }
      else -> runs.joinToString(" or ") { r ->
        val left = if (r.a.isFinite()) "${fmt(r.a)} ${if (r.openLeft) "<" else "≤"} " else ""
        val right = if (r.b.isFinite()) " ${if (r.openRight) "<" else "≤"} ${fmt(r.b)}" else ""
        when {
          left.isEmpty() && right.isEmpty() -> "all x"
          left.isEmpty() -> "x$right"
          right.isEmpty() -> "x ${if (r.openLeft) ">" else "≥"} ${fmt(r.a)}"
          else -> "${left}x$right"
        }
      }
    }

    // --- Zeros: sign changes, plus touching points and domain edges where f = 0
    val zeros = mutableListOf<Double>()
    fun addZero(z: Double) { val s = snap(z); if (zeros.none { abs(it - s) < 1e-6 }) zeros += s }
    for (k in 0 until N) {
      val a = ys[k]; val b = ys[k + 1]
      if (!a.isFinite() || !b.isFinite()) continue
      if (a == 0.0) { addZero(xs[k]); continue }
      if (a * b < 0) {
        var lo = xs[k]; var hi = xs[k + 1]; var flo = a
        repeat(70) { val m = 0.5 * (lo + hi); val fm = f(m); if (!fm.isFinite()) return@repeat; if (flo * fm <= 0) hi = m else { lo = m; flo = fm } }
        val z = 0.5 * (lo + hi)
        // A sign change across a pole isn't a zero
        if (abs(f(z)) < 1e-6 * (1 + abs(a) + abs(b))) addZero(z)
      }
    }
    for (r in runs) {
      if (r.a.isFinite() && abs(f(r.a)) < 1e-9) addZero(r.a)
      if (r.b.isFinite() && abs(f(r.b)) < 1e-9) addZero(r.b)
    }

    // --- Maxima and minima: where f' changes sign
    val extrema = mutableListOf<Extremum>()
    if (df != null) {
      val ds = DoubleArray(N + 1) { if (ok(it)) df(xs[it]) else Double.NaN }
      for (k in 0 until N) {
        val a = ds[k]; val b = ds[k + 1]
        if (!a.isFinite() || !b.isFinite() || a * b >= 0) {
          if (!(a == 0.0 && k > 0 && ds[k - 1].isFinite() && b.isFinite() && ds[k - 1] * b < 0)) continue
        }
        var lo = xs[k]; var hi = xs[k + 1]; var dlo = a
        if (a == 0.0) { lo = xs[k]; hi = xs[k] } else repeat(70) {
          val m = 0.5 * (lo + hi); val dm = df(m); if (!dm.isFinite()) return@repeat
          if (dlo * dm <= 0) hi = m else { lo = m; dlo = dm }
        }
        val x = snap(0.5 * (lo + hi)); val y = f(x)
        if (!y.isFinite() || poles.any { abs(it - x) < 1e-3 }) continue
        val isMax = (if (a == 0.0) ds[k - 1] else a) > 0
        if (extrema.none { abs(it.x - x) < 1e-6 }) extrema += Extremum(x, snap(y), isMax)
      }
    }
    // Zeros where the curve only touches the axis are minima/maxima with f = 0
    extrema.filter { abs(it.y) < 1e-9 }.forEach { addZero(it.x) }
    zeros.sort()

    // --- Horizontal asymptotes: f settles at the far ends
    fun settle(sign: Int): Double? {
      val a = f(sign * 1e6); val b = f(sign * 1e7)
      return if (a.isFinite() && b.isFinite() && abs(a - b) < 1e-4 * max(1.0, abs(b))) snap(b) else null
    }
    val right = settle(1); val left = settle(-1)

    // --- Symmetry, from points where both f(x) and f(−x) exist
    val probe = (1..24).map { 0.37 * it }.filter { f(it).isFinite() && f(-it).isFinite() }
    val symmetry = when {
      probe.size < 4 -> null
      probe.all { abs(f(-it) - f(it)) < 1e-8 * (1 + abs(f(it))) } -> "even"
      probe.all { abs(f(-it) + f(it)) < 1e-8 * (1 + abs(f(it))) } -> "odd"
      else -> null
    }

    val y0 = f(0.0).takeIf { it.isFinite() }?.let { snap(it) }
    return Result(domain, zeros, y0, extrema.sortedBy { it.x }, poles, right, left, symmetry)
  }

  /** The study as solution steps, and a one-line summary for the final answer. */
  fun describe(r: Result): Pair<List<String>, String> {
    val steps = mutableListOf<String>()
    val summary = mutableListOf<String>()
    steps += "Domain: " + (r.domain ?: "all real numbers")
    if (r.domain != null) summary += "Domain: ${r.domain}"
    steps += when (r.symmetry) {
      "even" -> "Symmetry: f(−x) = f(x), so f is even (symmetric about the y-axis)"
      "odd" -> "Symmetry: f(−x) = −f(x), so f is odd (symmetric about the origin)"
      else -> "Symmetry: neither even nor odd"
    }
    val z = r.zeros.take(8)
    steps += if (z.isEmpty()) "Zeros: f(x) is never 0" else "Zeros: " + z.joinToString(", ") { "x = ${fmt(it)}" }
    summary += if (z.isEmpty()) "No real zeros" else "Zeros: " + z.joinToString(", ") { "x = ${fmt(it)}" }
    r.yIntercept?.let { steps += "Crosses the y-axis at (0, ${fmt(it)})" }
    if (r.extrema.isEmpty()) steps += "No maximum or minimum: f' doesn't change sign"
    r.extrema.take(6).forEach { e ->
      val kind = if (e.isMax) "Maximum" else "Minimum"
      steps += "$kind at (${fmt(e.x)}, ${fmt(e.y)}): f' changes sign from ${if (e.isMax) "+ to −" else "− to +"} there"
      summary += "$kind (${fmt(e.x)}, ${fmt(e.y)})"
    }
    r.verticalAsymptotes.take(6).forEach {
      steps += "Vertical asymptote x = ${fmt(it)}: f grows without bound next to it"
      summary += "Asymptote x = ${fmt(it)}"
    }
    when {
      r.horizontalLeft != null && r.horizontalLeft == r.horizontalRight -> {
        steps += "Horizontal asymptote y = ${fmt(r.horizontalRight!!)} as x → ±∞"; summary += "Asymptote y = ${fmt(r.horizontalRight)}"
      }
      else -> {
        r.horizontalRight?.let { steps += "Horizontal asymptote y = ${fmt(it)} as x → +∞"; summary += "Asymptote y = ${fmt(it)} (x → +∞)" }
        r.horizontalLeft?.let { steps += "Horizontal asymptote y = ${fmt(it)} as x → −∞"; summary += "Asymptote y = ${fmt(it)} (x → −∞)" }
      }
    }
    return steps to summary.joinToString(" · ")
  }
}
