package com.deang.geminimath

import java.math.BigDecimal
import java.util.Locale
import kotlin.math.*

/**
 * Pure-Kotlin math core used by the solver: an expression tree, a parser for plain
 * expressions such as "2x e^(x) + sin x", number formatting, polynomial fitting,
 * symbolic differentiation and a rule-based integrator whose answers are checked numerically.
 * The only variable is x.
 */
sealed class Ex {
  data class Num(val v: Double) : Ex()
  data object X : Ex()
  data class Const(val name: String) : Ex() {
    val v: Double get() = if (name == "pi") PI else E
  }
  data class Neg(val a: Ex) : Ex()
  data class Bin(val op: Char, val a: Ex, val b: Ex) : Ex()
  data class Fn(val name: String, val arg: Ex) : Ex()
}

/** Solver preferences, set from the app settings. */
object MathSettings {
  @Volatile var angleDegrees = false
  @Volatile var exactFractions = true
  @Volatile var decimals = 4
}

class MathParseException(message: String) : Exception(message)

fun Ex.hasX(): Boolean = when (this) {
  Ex.X -> true
  is Ex.Neg -> a.hasX()
  is Ex.Bin -> a.hasX() || b.hasX()
  is Ex.Fn -> arg.hasX()
  else -> false
}

fun Ex.eval(x: Double, degrees: Boolean = false): Double = when (this) {
  is Ex.Num -> v
  Ex.X -> x
  is Ex.Const -> v
  is Ex.Neg -> -a.eval(x, degrees)
  is Ex.Bin -> {
    val l = a.eval(x, degrees)
    val r = b.eval(x, degrees)
    when (op) {
      '+' -> l + r
      '-' -> l - r
      '*' -> l * r
      '/' -> l / r
      '^' -> MathCore.pow(l, r)
      else -> Double.NaN
    }
  }
  is Ex.Fn -> MathCore.applyFn(name, arg.eval(x, degrees), degrees)
}

/** Fully parenthesised text that [ExParser] reads back exactly. */
fun Ex.toPlain(): String = when (this) {
  is Ex.Num -> MathFormat.plainNumber(v)
  Ex.X -> "x"
  is Ex.Const -> name
  is Ex.Neg -> "(-${a.toPlain()})"
  is Ex.Bin -> "(${a.toPlain()}$op${b.toPlain()})"
  is Ex.Fn -> if (name == "fact") "(${arg.toPlain()})!" else "$name(${arg.toPlain()})"
}

fun Ex.pretty(): String = MathFormat.pretty(this)

object MathCore {
  fun pow(l: Double, r: Double): Double {
    if (l < 0 && r != Math.rint(r)) {
      // Real odd roots of negative numbers, e.g. (-8)^(1/3) = -2
      for (q in intArrayOf(3, 5, 7, 9)) {
        val p = Math.rint(r * q)
        if (abs(r * q - p) < 1e-9 && p.toLong() % 2L != 0L) return -(-l).pow(r)
        if (abs(r * q - p) < 1e-9) return (-l).pow(r)
      }
      return Double.NaN
    }
    return l.pow(r)
  }

  fun applyFn(name: String, v: Double, degrees: Boolean): Double {
    val rad = if (degrees) Math.toRadians(v) else v
    fun inv(r: Double) = if (degrees) Math.toDegrees(r) else r
    return when (name) {
      "sin" -> sin(rad)
      "cos" -> cos(rad)
      "tan" -> tan(rad)
      "sec" -> 1.0 / cos(rad)
      "csc" -> 1.0 / sin(rad)
      "cot" -> 1.0 / tan(rad)
      "arcsin", "asin" -> inv(asin(v))
      "arccos", "acos" -> inv(acos(v))
      "arctan", "atan" -> inv(atan(v))
      "sinh" -> sinh(v)
      "cosh" -> cosh(v)
      "tanh" -> tanh(v)
      "sqrt" -> sqrt(v)
      "cbrt" -> cbrt(v)
      "exp" -> exp(v)
      "ln" -> ln(v)
      "log" -> log10(v)
      "abs" -> abs(v)
      "fact" -> factorial(v)
      else -> Double.NaN
    }
  }

  private fun factorial(v: Double): Double {
    if (v < 0 || v != Math.rint(v) || v > 170) return Double.NaN
    var r = 1.0
    for (i in 2..v.toInt()) r *= i
    return r
  }
}

/** Parser for plain expressions. Supports implicit multiplication (2x, x sin x, (x+1)(x-1)). */
object ExParser {
  private val FUNCTIONS = setOf(
    "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "asin", "acos", "atan",
    "sqrt", "cbrt", "sin", "cos", "tan", "sec", "csc", "cot", "exp", "abs", "log", "ln"
  )
  private val NAMES = (FUNCTIONS + setOf("pi", "e", "x")).sortedByDescending { it.length }

  private sealed class Tok {
    data class N(val v: Double) : Tok()
    data class Id(val s: String) : Tok()
    data class Op(val c: Char) : Tok()
  }

  fun parse(src: String): Ex {
    val toks = tokenize(src.lowercase(Locale.ROOT))
    if (toks.isEmpty()) throw MathParseException("Empty expression")
    val p = P(toks)
    val e = p.expr()
    if (p.i != toks.size) throw MathParseException("Unexpected token")
    return e
  }

  private fun tokenize(s: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      when {
        c.isWhitespace() -> i++
        c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
          val st = i
          while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
          out.add(Tok.N(s.substring(st, i).toDoubleOrNull() ?: throw MathParseException("Bad number")))
        }
        c == 'π' -> { out.add(Tok.Id("pi")); i++ }
        c.isLetter() -> {
          val name = NAMES.firstOrNull { s.startsWith(it, i) } ?: throw MathParseException("Unknown symbol '$c'")
          out.add(Tok.Id(name))
          i += name.length
        }
        c in "+-*/^()!" -> { out.add(Tok.Op(c)); i++ }
        c == '·' || c == '×' -> { out.add(Tok.Op('*')); i++ }
        c == '÷' -> { out.add(Tok.Op('/')); i++ }
        c == '−' -> { out.add(Tok.Op('-')); i++ }
        c == '[' -> { out.add(Tok.Op('(')); i++ }
        c == ']' -> { out.add(Tok.Op(')')); i++ }
        else -> throw MathParseException("Unexpected '$c'")
      }
    }
    return out
  }

  private class P(val t: List<Tok>) {
    var i = 0

    fun peek(): Tok? = t.getOrNull(i)
    fun isOp(c: Char) = (peek() as? Tok.Op)?.c == c
    fun eatOp(c: Char): Boolean {
      if (isOp(c)) { i++; return true }
      return false
    }

    fun expr(): Ex {
      var l = term()
      while (true) {
        l = when {
          eatOp('+') -> Ex.Bin('+', l, term())
          eatOp('-') -> Ex.Bin('-', l, term())
          else -> return l
        }
      }
    }

    fun startsAtom(): Boolean = when (val k = peek()) {
      is Tok.N, is Tok.Id -> true
      is Tok.Op -> k.c == '('
      null -> false
    }

    fun term(): Ex {
      var l = unary()
      while (true) {
        l = when {
          eatOp('*') -> Ex.Bin('*', l, unary())
          eatOp('/') -> Ex.Bin('/', l, unary())
          startsAtom() -> Ex.Bin('*', l, power())
          else -> return l
        }
      }
    }

    fun unary(): Ex = when {
      eatOp('-') -> Ex.Neg(unary())
      eatOp('+') -> unary()
      else -> power()
    }

    fun power(): Ex {
      val base = postfix()
      return if (eatOp('^')) Ex.Bin('^', base, unary()) else base
    }

    fun postfix(): Ex {
      var a = atom()
      while (eatOp('!')) a = Ex.Fn("fact", a)
      return a
    }

    fun group(): Ex {
      val e = expr()
      if (!eatOp(')')) throw MathParseException("Missing )")
      return e
    }

    fun atom(): Ex {
      val k = peek() ?: throw MathParseException("Unexpected end")
      i++
      return when (k) {
        is Tok.N -> Ex.Num(k.v)
        is Tok.Op -> if (k.c == '(') group() else throw MathParseException("Unexpected '${k.c}'")
        is Tok.Id -> when (k.s) {
          "x" -> Ex.X
          "pi", "e" -> Ex.Const(k.s)
          else -> {
            val fnPow = if (eatOp('^')) unary() else null // sin^2 x
            val arg = if (eatOp('(')) group() else fnArg()
            val f = Ex.Fn(k.s, arg)
            if (fnPow != null) Ex.Bin('^', f, fnPow) else f
          }
        }
      }
    }

    /** Argument written without brackets (sin 2x): numbers, x and constants up to the next function name. */
    fun fnArg(): Ex {
      var a = power()
      while (true) {
        val more = when (val k = peek()) {
          is Tok.N -> true
          is Tok.Id -> k.s == "x" || k.s == "pi" || k.s == "e"
          is Tok.Op -> k.c == '('
          null -> false
        }
        if (!more) return a
        a = Ex.Bin('*', a, power())
      }
    }
  }
}

object MathFormat {
  private const val SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹"

  fun plainNumber(v: Double): String {
    if (!v.isFinite()) return "(0/0)"
    val s = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
    return if (v < 0) "($s)" else s
  }

  fun sup(n: Int): String = (if (n < 0) "⁻" else "") + abs(n).toString().map { SUP[it - '0'] }.joinToString("")

  /** Number as shown to the user, honouring [MathSettings]. */
  fun num(v: Double): String {
    if (!v.isFinite()) return "undefined"
    val r = Math.rint(v)
    if (abs(v - r) < 1e-9 * max(1.0, abs(v))) return integer(r)
    if (MathSettings.exactFractions) {
      fraction(v)?.let { (p, q) -> return "$p/$q" }
      root(v)?.let { return it }
      expLog(v)?.let { return it }
    }
    val s = String.format(Locale.US, "%.${MathSettings.decimals}f", v).let {
      if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it
    }
    return if (s == "-0") "0" else s
  }

  /** Powers of e and logs of whole numbers, written exactly: e², √e, 1/e, ln 5, −ln 2. */
  private fun expLog(v: Double): String? {
    // Numerically found roots are good to about 10⁻⁹
    val tol = 1e-8 * max(1.0, abs(v))
    for (k in -4..6) if (k != 0 && abs(v - kotlin.math.exp(k.toDouble())) < tol) {
      return if (k == 1) "e" else "e" + sup(k)
    }
    if (abs(v - kotlin.math.sqrt(kotlin.math.E)) < tol) return "√e"
    for (n in 2..100) {
      val l = kotlin.math.ln(n.toDouble())
      if (abs(v - l) < tol) return "ln $n"
      if (abs(v + l) < tol) return "-ln $n"
    }
    return null
  }

  /** Like [num] but writes rational multiples of π as π/6, 5π/6, 2π … (for angles). */
  fun withPi(v: Double): String {
    if (!v.isFinite()) return "undefined"
    if (abs(v) < 1e-12) return "0"
    if (MathSettings.exactFractions) {
      val m = v / PI
      for (q in 1L..12L) {
        val p = Math.rint(m * q)
        if (p != 0.0 && abs(m * q - p) < 1e-7) {
          val pl = p.toLong()
          val head = when (pl) { 1L -> "π"; -1L -> "-π"; else -> "${pl}π" }
          return if (q == 1L) head else "$head/$q"
        }
      }
    }
    return num(v)
  }

  private fun integer(r: Double): String {
    if (r == 0.0) return "0"
    return if (abs(r) < 1e15) r.toLong().toString() else String.format(Locale.US, "%.6e", r)
  }

  fun fraction(v: Double): Pair<Long, Long>? {
    for (q in 2L..1000L) {
      val p = Math.rint(v * q)
      if (abs(v - p / q) < 1e-9 * max(1.0, abs(v))) return p.toLong() to q
    }
    return null
  }

  private fun root(v: Double): String? {
    val sq = v * v
    val n = Math.rint(sq)
    if (n < 2 || n > 10000 || abs(sq - n) > 1e-7 * n) return null
    val s = sqrt(n)
    if (s == Math.rint(s)) return null
    return (if (v < 0) "-" else "") + "√${n.toLong()}"
  }

  /** c·thing, e.g. ("eˣ", 1/2) → "eˣ/2", (-1) → "-eˣ", 3 → "3eˣ". [thing] must read as one factor. */
  fun coefTimes(c: Double, thing: String): String {
    val s = num(c)
    return when {
      s == "1" -> thing
      s == "-1" -> "-$thing"
      s.contains('/') -> {
        val (p, q) = s.split('/')
        when (p) {
          "1" -> "$thing/$q"
          "-1" -> "-$thing/$q"
          else -> "$p$thing/$q"
        }
      }
      s.contains('√') || s.contains('.') -> "$s·$thing"
      else -> "$s$thing"
    }
  }

  /** k·(inner) for a compound [inner] such as "x·ln(x) - x". */
  fun scaled(k: Double, inner: String): String {
    val s = num(k)
    return when {
      s == "1" -> inner
      s == "-1" -> "-($inner)"
      s.contains('/') -> {
        val (p, q) = s.split('/')
        when (p) {
          "1" -> "($inner)/$q"
          "-1" -> "-($inner)/$q"
          else -> "$p($inner)/$q"
        }
      }
      else -> "$s($inner)"
    }
  }

  fun monomial(c: Double, k: Int): String = if (k == 0) num(c) else coefTimes(c, if (k == 1) "x" else "x${sup(k)}")

  /** Polynomial from coefficients (c[k] multiplies xᵏ), highest power first. */
  fun poly(c: DoubleArray): String {
    val terms = (c.indices.reversed()).filter { abs(c[it]) > 1e-12 }.map { monomial(c[it], it) }
    return if (terms.isEmpty()) "0" else joinSigned(terms)
  }

  fun polyPlain(c: DoubleArray): String {
    if (c.all { abs(it) < 1e-12 }) return "0"
    return "(" + c.indices.filter { abs(c[it]) > 1e-12 }.joinToString("+") { k ->
      when (k) {
        0 -> plainNumber(c[k])
        1 -> "${plainNumber(c[k])}*x"
        else -> "${plainNumber(c[k])}*x^$k"
      }
    } + ")"
  }

  fun joinSigned(parts: List<String>): String {
    val sb = StringBuilder()
    parts.filter { it.isNotBlank() && it != "0" }.forEachIndexed { i, p ->
      when {
        i == 0 -> sb.append(p)
        p.startsWith("-") -> sb.append(" - ").append(p.substring(1))
        else -> sb.append(" + ").append(p)
      }
    }
    return if (sb.isEmpty()) "0" else sb.toString()
  }

  /** True when [s] has a top-level + or - (so it needs brackets as a factor). */
  fun isCompound(s: String): Boolean {
    var depth = 0
    s.forEachIndexed { i, c ->
      when (c) {
        '(', '|' -> depth++
        ')' -> depth--
        '+', '-' -> if (depth == 0 && i > 0) return true
      }
    }
    return false
  }

  fun pretty(e: Ex, parent: Int = 0): String {
    fun wrap(s: String, prec: Int) = if (parent > prec) "($s)" else s
    return when (e) {
      is Ex.Num -> num(e.v).let { if (e.v < 0 && parent >= 2) "($it)" else it }
      Ex.X -> "x"
      is Ex.Const -> if (e.name == "pi") "π" else "e"
      is Ex.Neg -> wrap("-" + pretty(e.a, 2), 2)
      is Ex.Bin -> when (e.op) {
        '+' -> wrap(joinSigned(listOf(pretty(e.a, 1), pretty(e.b, 1))), 1)
        '-' -> wrap(pretty(e.a, 1) + " - " + pretty(e.b, 2), 1)
        '*' -> {
          val l = pretty(e.a, 2)
          val r = pretty(e.b, 2)
          val juxtapose = e.a is Ex.Num && e.a.v >= 0 && e.b !is Ex.Num && !r.first().isDigit()
          wrap(if (juxtapose) l + r else "$l·$r", 2)
        }
        '/' -> {
          // Keep a simple product like 2x unbracketed on top: 2x/(x² + 1)
          val top = if (e.a is Ex.Bin && e.a.op == '*' && e.a.a is Ex.Num) pretty(e.a, 2) else pretty(e.a, 3)
          wrap(top + "/" + pretty(e.b, 3), 2)
        }
        '^' -> {
          val b = e.b
          val base = pretty(e.a, 5)
          val text = when {
            b is Ex.Num && b.v == Math.rint(b.v) && abs(b.v) < 100 -> base + sup(b.v.toInt())
            e.a is Ex.Const && b == Ex.X -> base + "ˣ"
            else -> base + "^" + pretty(b, 5)
          }
          wrap(text, 4)
        }
        else -> "?"
      }
      is Ex.Fn -> when (e.name) {
        "sqrt" -> if (e.arg is Ex.X || e.arg is Ex.Num) "√${pretty(e.arg)}" else "√(${pretty(e.arg)})"
        "abs" -> "|${pretty(e.arg)}|"
        "fact" -> pretty(e.arg, 5) + "!"
        else -> "${e.name}(${pretty(e.arg)})"
      }
    }
  }
}

/** Fits a polynomial to a function by sampling. Returns coefficients (c[k] for xᵏ) or null. */
object PolyFit {
  private val NODES = doubleArrayOf(-2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0)
  private val CHECKS = doubleArrayOf(-2.7, -0.37, 0.61, 1.9, 3.3)

  fun fit(f: (Double) -> Double): DoubleArray? {
    val n = NODES.size
    val ys = DoubleArray(n)
    for (i in 0 until n) {
      val y = f(NODES[i])
      if (!y.isFinite()) return null
      ys[i] = y
    }
    val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c == n) ys[r] else NODES[r].pow(c) } }
    for (col in 0 until n) {
      var piv = col
      for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
      val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
      if (abs(m[col][col]) < 1e-14) return null
      for (r in 0 until n) {
        if (r == col) continue
        val factor = m[r][col] / m[col][col]
        if (factor != 0.0) for (c in col..n) m[r][c] -= factor * m[col][c]
      }
    }
    val raw = DoubleArray(n) { m[it][n] / m[it][it] }
    val scale = max(1.0, raw.maxOf { abs(it) })
    val coeffs = raw.map { c -> if (abs(c) < 1e-8 * scale) 0.0 else snap(c) }.toDoubleArray()
    for (x in CHECKS) {
      val y = f(x)
      if (!y.isFinite()) return null
      if (abs(evalPoly(coeffs, x) - y) > 1e-6 * (1 + abs(y))) return null
    }
    var deg = coeffs.size - 1
    while (deg > 0 && coeffs[deg] == 0.0) deg--
    return coeffs.copyOf(deg + 1)
  }

  private fun snap(c: Double): Double {
    val r = Math.rint(c)
    if (abs(c - r) < 1e-8) return r
    MathFormat.fraction(c)?.let { (p, q) -> if (abs(c - p.toDouble() / q) < 1e-8) return p.toDouble() / q }
    return c
  }

  fun evalPoly(c: DoubleArray, x: Double): Double {
    var y = 0.0
    for (k in c.indices.reversed()) y = y * x + c[k]
    return y
  }

  fun deriv(c: DoubleArray): DoubleArray =
    if (c.size <= 1) doubleArrayOf() else DoubleArray(c.size - 1) { (it + 1) * c[it + 1] }

  fun integ(c: DoubleArray): DoubleArray = DoubleArray(c.size + 1) { if (it == 0) 0.0 else c[it - 1] / it }

  fun degree(c: DoubleArray): Int {
    var d = c.size - 1
    while (d > 0 && abs(c[d]) < 1e-12) d--
    return d
  }

  /** Long division: returns (quotient, remainder). */
  fun divide(p: DoubleArray, q: DoubleArray): Pair<DoubleArray, DoubleArray> {
    val dq = degree(q)
    val rem = p.copyOf()
    val dp = degree(p)
    if (dp < dq) return doubleArrayOf(0.0) to rem
    val quot = DoubleArray(dp - dq + 1)
    for (k in (dp - dq) downTo 0) {
      val coef = rem[k + dq] / q[dq]
      quot[k] = coef
      for (j in 0..dq) rem[k + j] -= coef * q[j]
    }
    return quot to rem.copyOf(max(1, dq))
  }
}

/** Symbolic derivative with light simplification. */
object Deriv {
  private val ONE = Ex.Num(1.0)
  private val ZERO = Ex.Num(0.0)

  fun of(e: Ex): Ex = simplify(raw(e))

  private fun raw(e: Ex): Ex = when (e) {
    is Ex.Num, is Ex.Const -> ZERO
    Ex.X -> ONE
    is Ex.Neg -> Ex.Neg(raw(e.a))
    is Ex.Bin -> when (e.op) {
      '+', '-' -> Ex.Bin(e.op, raw(e.a), raw(e.b))
      '*' -> Ex.Bin('+', Ex.Bin('*', raw(e.a), e.b), Ex.Bin('*', e.a, raw(e.b)))
      '/' -> Ex.Bin(
        '/',
        Ex.Bin('-', Ex.Bin('*', raw(e.a), e.b), Ex.Bin('*', e.a, raw(e.b))),
        Ex.Bin('^', e.b, Ex.Num(2.0))
      )
      '^' -> when {
        !e.b.hasX() -> Ex.Bin('*', Ex.Bin('*', e.b, Ex.Bin('^', e.a, Ex.Bin('-', e.b, ONE))), raw(e.a))
        !e.a.hasX() && e.a is Ex.Const && e.a.name == "e" -> Ex.Bin('*', e, raw(e.b))
        !e.a.hasX() -> Ex.Bin('*', Ex.Bin('*', e, Ex.Fn("ln", e.a)), raw(e.b))
        else -> Ex.Bin(
          '*', e,
          Ex.Bin('+', Ex.Bin('*', raw(e.b), Ex.Fn("ln", e.a)), Ex.Bin('/', Ex.Bin('*', e.b, raw(e.a)), e.a))
        )
      }
      else -> Ex.Num(Double.NaN)
    }
    is Ex.Fn -> {
      val u = e.arg
      val outer: Ex = when (e.name) {
        "sin" -> Ex.Fn("cos", u)
        "cos" -> Ex.Neg(Ex.Fn("sin", u))
        "tan" -> Ex.Bin('/', ONE, Ex.Bin('^', Ex.Fn("cos", u), Ex.Num(2.0)))
        "sec" -> Ex.Bin('*', e, Ex.Fn("tan", u))
        "csc" -> Ex.Neg(Ex.Bin('*', e, Ex.Fn("cot", u)))
        "cot" -> Ex.Neg(Ex.Bin('/', ONE, Ex.Bin('^', Ex.Fn("sin", u), Ex.Num(2.0))))
        "exp" -> e
        "ln" -> Ex.Bin('/', ONE, u)
        "log" -> Ex.Bin('/', ONE, Ex.Bin('*', u, Ex.Num(ln(10.0))))
        "sqrt" -> Ex.Bin('/', ONE, Ex.Bin('*', Ex.Num(2.0), e))
        "cbrt" -> Ex.Bin('/', ONE, Ex.Bin('*', Ex.Num(3.0), Ex.Bin('^', e, Ex.Num(2.0))))
        "abs" -> Ex.Bin('/', u, e)
        "arcsin", "asin" -> Ex.Bin('/', ONE, Ex.Fn("sqrt", Ex.Bin('-', ONE, Ex.Bin('^', u, Ex.Num(2.0)))))
        "arccos", "acos" -> Ex.Neg(Ex.Bin('/', ONE, Ex.Fn("sqrt", Ex.Bin('-', ONE, Ex.Bin('^', u, Ex.Num(2.0))))))
        "arctan", "atan" -> Ex.Bin('/', ONE, Ex.Bin('+', ONE, Ex.Bin('^', u, Ex.Num(2.0))))
        "sinh" -> Ex.Fn("cosh", u)
        "cosh" -> Ex.Fn("sinh", u)
        "tanh" -> Ex.Bin('/', ONE, Ex.Bin('^', Ex.Fn("cosh", u), Ex.Num(2.0)))
        else -> Ex.Num(Double.NaN)
      }
      Ex.Bin('*', outer, raw(u))
    }
  }

  private fun isNum(e: Ex, v: Double) = e is Ex.Num && e.v == v
  private fun onlyNumbers(e: Ex): Boolean = when (e) {
    is Ex.Num -> true
    is Ex.Neg -> onlyNumbers(e.a)
    is Ex.Bin -> onlyNumbers(e.a) && onlyNumbers(e.b)
    is Ex.Fn -> false
    else -> false
  }

  fun simplify(e: Ex): Ex {
    val s: Ex = when (e) {
      is Ex.Neg -> {
        val a = simplify(e.a)
        when (a) {
          is Ex.Num -> Ex.Num(-a.v)
          is Ex.Neg -> a.a
          else -> Ex.Neg(a)
        }
      }
      is Ex.Bin -> {
        val a = simplify(e.a)
        val b = simplify(e.b)
        when (e.op) {
          '+' -> when {
            isNum(a, 0.0) -> b
            isNum(b, 0.0) -> a
            b is Ex.Neg -> Ex.Bin('-', a, b.a)
            b is Ex.Num && b.v < 0 -> Ex.Bin('-', a, Ex.Num(-b.v))
            else -> Ex.Bin('+', a, b)
          }
          '-' -> when {
            isNum(b, 0.0) -> a
            isNum(a, 0.0) -> simplify(Ex.Neg(b))
            b is Ex.Neg -> Ex.Bin('+', a, b.a)
            a == b -> ZERO
            else -> Ex.Bin('-', a, b)
          }
          '*' -> when {
            isNum(a, 0.0) || isNum(b, 0.0) -> ZERO
            isNum(a, 1.0) -> b
            isNum(b, 1.0) -> a
            a is Ex.Neg -> simplify(Ex.Neg(Ex.Bin('*', a.a, b)))
            b is Ex.Neg -> simplify(Ex.Neg(Ex.Bin('*', a, b.a)))
            a is Ex.Num && a.v < 0 -> simplify(Ex.Neg(Ex.Bin('*', Ex.Num(-a.v), b)))
            b is Ex.Num && a !is Ex.Num -> simplify(Ex.Bin('*', b, a))
            a is Ex.Num && b is Ex.Bin && b.op == '*' && b.a is Ex.Num ->
              simplify(Ex.Bin('*', Ex.Num(a.v * b.a.v), b.b))
            // (1/u)·v → v/u
            a is Ex.Bin && a.op == '/' && isNum(a.a, 1.0) -> simplify(Ex.Bin('/', b, a.b))
            b is Ex.Bin && b.op == '/' && isNum(b.a, 1.0) -> simplify(Ex.Bin('/', a, b.b))
            a == b -> Ex.Bin('^', a, Ex.Num(2.0))
            else -> Ex.Bin('*', a, b)
          }
          '/' -> when {
            isNum(a, 0.0) -> ZERO
            isNum(b, 1.0) -> a
            a == b -> ONE
            a is Ex.Neg -> simplify(Ex.Neg(Ex.Bin('/', a.a, b)))
            a is Ex.Num && a.v < 0 -> simplify(Ex.Neg(Ex.Bin('/', Ex.Num(-a.v), b)))
            else -> Ex.Bin('/', a, b)
          }
          '^' -> when {
            isNum(b, 0.0) -> ONE
            isNum(b, 1.0) -> a
            else -> Ex.Bin('^', a, b)
          }
          else -> Ex.Bin(e.op, a, b)
        }
      }
      is Ex.Fn -> {
        val arg = simplify(e.arg)
        if (e.name == "ln" && arg == Ex.Const("e")) ONE else Ex.Fn(e.name, arg)
      }
      else -> e
    }
    // Fold pure-number sub-expressions
    if (s !is Ex.Num && onlyNumbers(s)) {
      val v = s.eval(0.0)
      if (v.isFinite()) return Ex.Num(v)
    }
    return s
  }
}

/**
 * Rule-based integrator: polynomials, linear substitutions, polynomial × exp / sin / cos / ln
 * (integration by parts), rational functions with linear or quadratic denominators and powers of
 * linear terms. Every answer is checked by differentiating it numerically; anything that fails
 * the check is rejected instead of shown.
 */
object Integrator {
  data class Result(val expr: String, val pretty: String, val steps: List<String>)

  private data class Lin(val a: Double, val b: Double) {
    val isX get() = a == 1.0 && b == 0.0
    val pretty get() = if (isX) "x" else MathFormat.poly(doubleArrayOf(b, a))
    val plain get() = "(${MathFormat.plainNumber(a)}*x+${MathFormat.plainNumber(b)})"
    val wrapped get() = if (isX) "x" else "($pretty)"
  }

  fun integrate(f: Ex): Result? {
    val r = runCatching { integrateSum(f) }.getOrNull() ?: return null
    return if (verify(f, r.expr)) r else null
  }

  private fun verify(f: Ex, primitive: String): Boolean {
    val big = runCatching { ExParser.parse(primitive) }.getOrNull() ?: return false
    var ok = 0
    for (x in doubleArrayOf(0.31, 0.73, 1.37, 2.11, -0.62, -1.73, 2.9)) {
      val fx = f.eval(x)
      val h = 1e-5 * max(1.0, abs(x))
      val d = (big.eval(x + h) - big.eval(x - h)) / (2 * h)
      if (!fx.isFinite() || !d.isFinite()) continue
      if (abs(d - fx) > 1e-4 * (1 + abs(fx))) return false
      ok++
    }
    return ok >= 3
  }

  private fun integrateSum(f: Ex): Result? {
    PolyFit.fit { f.eval(it) }?.let { return polyResult(it) }
    val terms = ArrayList<Pair<Double, Ex>>()
    flattenSum(f, 1.0, terms)
    if (terms.size == 1) return integrateTerm(terms[0].second, terms[0].first)

    // Gather polynomial terms into one, integrate the rest one by one
    var polySum = doubleArrayOf(0.0)
    val others = ArrayList<Result>()
    for ((sign, t) in terms) {
      val p = PolyFit.fit { sign * t.eval(it) }
      if (p != null) {
        polySum = DoubleArray(max(polySum.size, p.size)) { (polySum.getOrNull(it) ?: 0.0) + (p.getOrNull(it) ?: 0.0) }
      } else {
        others.add(integrateTerm(t, sign) ?: return null)
      }
    }
    val parts = ArrayList<Result>()
    if (polySum.any { it != 0.0 }) parts.add(polyResult(polySum))
    parts.addAll(others)
    return Result(
      expr = parts.joinToString("+") { "(${it.expr})" },
      pretty = MathFormat.joinSigned(parts.map { it.pretty }),
      steps = listOf("Integrate term by term: ∫(f + g) dx = ∫f dx + ∫g dx") +
        parts.map { "∫ … = ${it.pretty}  —  ${it.steps.firstOrNull() ?: ""}" }
    )
  }

  private fun flattenSum(e: Ex, sign: Double, out: MutableList<Pair<Double, Ex>>) {
    when {
      e is Ex.Bin && e.op == '+' -> { flattenSum(e.a, sign, out); flattenSum(e.b, sign, out) }
      e is Ex.Bin && e.op == '-' -> { flattenSum(e.a, sign, out); flattenSum(e.b, -sign, out) }
      e is Ex.Neg -> flattenSum(e.a, -sign, out)
      else -> out.add(sign to e)
    }
  }

  private fun polyResult(p: DoubleArray): Result {
    val q = PolyFit.integ(p)
    val step = if (PolyFit.degree(p) == 0) "Integrate the constant: ∫ c dx = c·x"
    else "Apply the power rule ∫ xⁿ dx = xⁿ⁺¹/(n+1) to each term"
    return Result(MathFormat.polyPlain(q), MathFormat.poly(q), listOf(step))
  }

  private fun linear(u: Ex): Lin? {
    val c = PolyFit.fit { u.eval(it) } ?: return null
    if (c.size != 2 || c[1] == 0.0) return null
    return Lin(c[1], c[0])
  }

  private fun scale(p: DoubleArray, k: Double) = DoubleArray(p.size) { p[it] * k }

  private fun integrateTerm(t: Ex, sign: Double): Result? {
    PolyFit.fit { sign * t.eval(it) }?.let { return polyResult(it) }
    val num = ArrayList<Ex>()
    val den = ArrayList<Ex>()
    var k = sign
    fun collect(e: Ex, inDen: Boolean) {
      when {
        e is Ex.Neg -> { k = -k; collect(e.a, inDen) }
        e is Ex.Bin && e.op == '*' -> { collect(e.a, inDen); collect(e.b, inDen) }
        e is Ex.Bin && e.op == '/' -> { collect(e.a, inDen); collect(e.b, !inDen) }
        e is Ex.Bin && e.op == '^' && e.a.hasX() && !e.b.hasX() && e.b.eval(0.0) < 0 ->
          collect(Ex.Bin('^', e.a, Ex.Num(-e.b.eval(0.0))), !inDen)
        !e.hasX() -> {
          val v = e.eval(0.0)
          k = if (inDen) k / v else k * v
        }
        else -> (if (inDen) den else num).add(e)
      }
    }
    collect(t, false)
    if (!k.isFinite() || k == 0.0) return null

    xPowerTimesLn(num, den, k)?.let { return it }
    powersOfX(num, den, k)?.let { return it }
    if (den.isEmpty()) trigProducts(num, k)?.let { return it }

    fun isPoly(g: Ex) = PolyFit.fit { g.eval(it) } != null
    fun productPoly(fs: List<Ex>): DoubleArray? =
      if (fs.isEmpty()) doubleArrayOf(1.0) else PolyFit.fit { x -> fs.fold(1.0) { acc, g -> acc * g.eval(x) } }

    if (den.isEmpty()) {
      val special = num.filter { !isPoly(it) }
      if (special.size != 1) return null
      val s = special[0]
      val p = productPoly(num - s)?.let { scale(it, k) } ?: return null
      return specialRule(s, p)
    }
    if (num.any { !isPoly(it) }) return null
    val p = scale(productPoly(num) ?: return null, k)
    val q = productPoly(den)
    if (q != null) return rationalRule(p, q)
    if (den.size == 1 && PolyFit.degree(p) == 0) {
      val (lin, r) = linearPower(den[0]) ?: return null
      return powerRule(p[0], lin, -r)
    }
    return null
  }

  private fun specialRule(s: Ex, p: DoubleArray): Result? {
    expOf(s)?.let { (lin, base) -> return expRule(p, lin, base) }
    if (s is Ex.Fn) {
      val lin = linear(s.arg)
      if (lin != null) when (s.name) {
        "sin", "cos" -> return trigRule(p, s.name, lin)
        "ln" -> return lnRule(p, lin)
        "tan" -> if (PolyFit.degree(p) == 0) return tanRule(p[0], lin)
      }
    }
    linearPower(s)?.let { (lin, r) -> if (PolyFit.degree(p) == 0) return powerRule(p[0], lin, r) }
    return null
  }

  /** e^u, exp(u) or c^u with u linear. Returns the exponent and the base (e for natural). */
  private fun expOf(s: Ex): Pair<Lin, Ex>? {
    val (base, u) = when {
      s is Ex.Fn && s.name == "exp" -> Ex.Const("e") to s.arg
      s is Ex.Bin && s.op == '^' && !s.a.hasX() && s.b.hasX() -> s.a to s.b
      else -> return null
    }
    val bv = base.eval(0.0)
    if (!(bv > 0) || bv == 1.0) return null
    return (linear(u) ?: return null) to base
  }

  private fun linearPower(g: Ex): Pair<Lin, Double>? = when {
    g is Ex.Fn && g.name == "sqrt" -> linear(g.arg)?.let { it to 0.5 }
    g is Ex.Fn && g.name == "cbrt" -> linear(g.arg)?.let { it to 1.0 / 3.0 }
    g is Ex.Bin && g.op == '^' && !g.b.hasX() -> linear(g.a)?.let { it to g.b.eval(0.0) }
    else -> null
  }

  private fun expRule(p: DoubleArray, lin: Lin, base: Ex): Result {
    val isE = base is Ex.Const && base.name == "e"
    val a = lin.a * ln(base.eval(0.0))
    // ∫P·e^u dx = e^u · Σ (-1)^k P⁽ᵏ⁾ / a^(k+1)
    val q = DoubleArray(p.size)
    var d = p
    var k = 0
    while (d.isNotEmpty()) {
      val f = (if (k % 2 == 0) 1.0 else -1.0) / a.pow(k + 1)
      for (i in d.indices) q[i] += d[i] * f
      d = PolyFit.deriv(d)
      k++
    }
    val basePretty = MathFormat.pretty(base, 5)
    val ePretty = when {
      lin.isX -> basePretty + "ˣ"
      else -> "$basePretty^(${lin.pretty})"
    }
    val ePlain = if (isE) "exp(${lin.plain})" else "(${base.toPlain()})^(${lin.plain})"
    val pretty = if (PolyFit.degree(q) == 0) MathFormat.coefTimes(q[0], ePretty)
    else "$ePretty(${MathFormat.poly(q)})"
    val steps = if (PolyFit.degree(p) == 0) {
      listOf(
        if (lin.isX && isE) "Use ∫ eˣ dx = eˣ" else "Substitute u = ${lin.pretty}: ∫ $ePretty dx = $ePretty / ${MathFormat.num(a)}",
        "Result: $pretty + C"
      )
    } else {
      listOfNotNull(
        "Integrate by parts, ∫ u dv = u·v - ∫ v du, with u = ${MathFormat.poly(p)} and dv = $ePretty dx",
        if (PolyFit.degree(p) > 1) "Repeat integration by parts until the polynomial factor is gone (${PolyFit.degree(p)} times)" else null,
        "Collect the terms: $pretty + C"
      )
    }
    return Result("$ePlain*${MathFormat.polyPlain(q)}", pretty, steps)
  }

  private fun trigRule(p: DoubleArray, name: String, lin: Lin): Result {
    val a = lin.a
    val cosCoef = DoubleArray(p.size)
    val sinCoef = DoubleArray(p.size)
    var d = p
    var k = 0
    while (d.isNotEmpty()) {
      val f = 1.0 / a.pow(k + 1)
      val sgn = if ((k / 2) % 2 == 0) 1.0 else -1.0
      for (i in d.indices) {
        val v = d[i] * f * sgn
        if (name == "sin") {
          if (k % 2 == 0) cosCoef[i] -= v else sinCoef[i] += v
        } else {
          if (k % 2 == 0) sinCoef[i] += v else cosCoef[i] += v
        }
      }
      d = PolyFit.deriv(d)
      k++
    }
    val cosP = "cos(${lin.pretty})"
    val sinP = "sin(${lin.pretty})"
    val pretty = MathFormat.joinSigned(listOf(polyTimes(cosCoef, cosP), polyTimes(sinCoef, sinP)))
    val expr = "${MathFormat.polyPlain(cosCoef)}*cos(${lin.plain})+${MathFormat.polyPlain(sinCoef)}*sin(${lin.plain})"
    val fn = "$name(${lin.pretty})"
    val steps = if (PolyFit.degree(p) == 0) {
      listOf(
        if (name == "sin") "Use ∫ sin(u) du = -cos(u)" + (if (lin.isX) "" else " with u = ${lin.pretty}, du = ${MathFormat.num(a)} dx")
        else "Use ∫ cos(u) du = sin(u)" + (if (lin.isX) "" else " with u = ${lin.pretty}, du = ${MathFormat.num(a)} dx"),
        "Result: $pretty + C"
      )
    } else {
      listOfNotNull(
        "Integrate by parts, ∫ u dv = u·v - ∫ v du, with u = ${MathFormat.poly(p)} and dv = $fn dx",
        if (PolyFit.degree(p) > 1) "Repeat integration by parts (${PolyFit.degree(p)} times)" else null,
        "Collect the terms: $pretty + C"
      )
    }
    return Result(expr, pretty, steps)
  }

  /** P(x)·thing, written compactly: "x·cos(x)", "-cos(x)", "(x² - 2)·sin(x)". */
  private fun polyTimes(c: DoubleArray, thing: String): String {
    val nz = c.indices.filter { abs(c[it]) > 1e-12 }
    return when {
      nz.isEmpty() -> "0"
      nz.size == 1 && nz[0] == 0 -> MathFormat.coefTimes(c[0], thing)
      nz.size == 1 -> MathFormat.monomial(c[nz[0]], nz[0]) + "·" + thing
      else -> "(${MathFormat.poly(c)})·$thing"
    }
  }

  private fun lnRule(p: DoubleArray, lin: Lin): Result? {
    if (lin.isX) {
      val q = PolyFit.integ(p)
      val r = DoubleArray(q.size) { if (it == 0) 0.0 else q[it] / it }
      val pretty = MathFormat.joinSigned(listOf(polyTimes(q, "ln(x)"), "-" + wrapIfCompound(MathFormat.poly(r))))
      return Result(
        "${MathFormat.polyPlain(q)}*ln(x)-${MathFormat.polyPlain(r)}",
        pretty,
        listOf(
          "Integrate by parts with u = ln(x), dv = ${MathFormat.poly(p)} dx ⇒ du = dx/x, v = ${MathFormat.poly(q)}",
          "Result: $pretty + C"
        )
      )
    }
    if (PolyFit.degree(p) != 0) return null
    val k = p[0] / lin.a
    val inner = "${lin.wrapped}·ln${lin.wrapped} - ${lin.wrapped}"
    return Result(
      "${MathFormat.plainNumber(k)}*(${lin.plain}*ln(${lin.plain})-${lin.plain})",
      MathFormat.scaled(k, inner),
      listOf("Substitute u = ${lin.pretty} and use ∫ ln(u) du = u·ln(u) - u")
    )
  }

  private fun wrapIfCompound(s: String) = if (MathFormat.isCompound(s)) "($s)" else s

  private fun powerOfX(g: Ex): Double? = if (g == Ex.X) 1.0 else linearPower(g)?.takeIf { it.first.isX }?.second

  /** Products of powers of x such as x·√x or √x/x²: combine the exponents, then the power rule. */
  private fun powersOfX(num: List<Ex>, den: List<Ex>, k: Double): Result? {
    if (num.isEmpty() && den.isEmpty()) return null
    var r = 0.0
    for (g in num) r += powerOfX(g) ?: return null
    for (g in den) r -= powerOfX(g) ?: return null
    val res = powerRule(k, Lin(1.0, 0.0), r)
    return res.copy(steps = listOf("Combine the powers of x: x^(${MathFormat.num(r)})") + res.steps)
  }

  private fun sameLin(a: Ex, b: Ex): Lin? {
    val la = linear(a) ?: return null
    val lb = linear(b) ?: return null
    return if (abs(la.a - lb.a) < 1e-12 && abs(la.b - lb.b) < 1e-12) la else null
  }

  /** sin²u, cos²u, sin u·cos u and e^(px+q)·sin/cos(bx+c). */
  private fun trigProducts(num: List<Ex>, k: Double): Result? {
    fun trigOf(g: Ex): Pair<String, Ex>? = (g as? Ex.Fn)?.takeIf { it.name == "sin" || it.name == "cos" }?.let { it.name to it.arg }

    // sin²(u) or cos²(u)
    if (num.size == 1) {
      val g = num[0]
      if (g is Ex.Bin && g.op == '^' && g.b == Ex.Num(2.0)) {
        val (name, u) = trigOf(g.a) ?: return null
        val lin = linear(u) ?: return null
        val sign = if (name == "sin") -1.0 else 1.0
        val c2 = k * sign / (4 * lin.a)
        val twoU = Lin(2 * lin.a, 2 * lin.b)
        val pretty = MathFormat.joinSigned(listOf(MathFormat.coefTimes(k / 2, "x"), MathFormat.coefTimes(c2, "sin(${twoU.pretty})")))
        return Result(
          "${MathFormat.plainNumber(k / 2)}*x+${MathFormat.plainNumber(c2)}*sin(${twoU.plain})",
          pretty,
          listOf(
            "Use the identity $name²(u) = (1 ${if (name == "sin") "-" else "+"} cos(2u))/2",
            "Integrate each term: $pretty + C"
          )
        )
      }
      return null
    }
    if (num.size != 2) return null
    val t0 = trigOf(num[0])
    val t1 = trigOf(num[1])

    // sin(u)·cos(u) = sin(2u)/2  ⇒  ∫ = sin²(u)/(2a)
    if (t0 != null && t1 != null && t0.first != t1.first) {
      val lin = sameLin(t0.second, t1.second) ?: return null
      val c = k / (2 * lin.a)
      val pretty = MathFormat.coefTimes(c, "sin²(${lin.pretty})")
      return Result(
        "${MathFormat.plainNumber(c)}*sin(${lin.plain})^2",
        pretty,
        listOf("Substitute w = sin(${lin.pretty}), dw = ${MathFormat.coefTimes(lin.a, "cos(${lin.pretty})")} dx", "Result: $pretty + C")
      )
    }

    // e^(px+q)·sin(bx+c) or ·cos(bx+c)
    val trig = t0 ?: t1 ?: return null
    val other = if (t0 != null) num[1] else num[0]
    val (expLin, base) = expOf(other) ?: return null
    if (!(base is Ex.Const && base.name == "e")) return null
    val tl = linear(trig.second) ?: return null
    val p = expLin.a
    val b = tl.a
    val d = p * p + b * b
    val (sinC, cosC) = if (trig.first == "sin") (k * p / d) to (-k * b / d) else (k * b / d) to (k * p / d)
    val ePretty = if (expLin.isX) "eˣ" else "e^(${expLin.pretty})"
    val inner = MathFormat.joinSigned(listOf(MathFormat.coefTimes(sinC, "sin(${tl.pretty})"), MathFormat.coefTimes(cosC, "cos(${tl.pretty})")))
    val pretty = "$ePretty($inner)"
    return Result(
      "exp(${expLin.plain})*(${MathFormat.plainNumber(sinC)}*sin(${tl.plain})+${MathFormat.plainNumber(cosC)}*cos(${tl.plain}))",
      pretty,
      listOf(
        "Integrate by parts twice; the original integral appears again on the right-hand side",
        "Solve for the integral: $pretty + C"
      )
    )
  }

  /** k·xʳ·ln(x) with any real r (e.g. ln(x)/√x): ∫ = k·xʳ⁺¹(ln(x)/(r+1) - 1/(r+1)²). */
  private fun xPowerTimesLn(num: List<Ex>, den: List<Ex>, k: Double): Result? {
    val lnCount = num.count { it is Ex.Fn && it.name == "ln" && it.arg == Ex.X }
    if (lnCount != 1) return null
    var r = 0.0
    fun powerOfX(g: Ex): Double? = if (g == Ex.X) 1.0 else linearPower(g)?.takeIf { it.first.isX }?.second
    for (g in num) {
      if (g is Ex.Fn && g.name == "ln" && g.arg == Ex.X) continue
      r += powerOfX(g) ?: return null
    }
    for (g in den) r -= powerOfX(g) ?: return null
    if (r == Math.rint(r) && r >= 0) return null // plain polynomial × ln(x): handled by lnRule
    if (abs(r + 1) < 1e-12) {
      return Result(
        "${MathFormat.plainNumber(k / 2)}*ln(x)^2",
        MathFormat.coefTimes(k / 2, "ln(x)²"),
        listOf("Substitute u = ln(x), du = dx/x: ∫ u du = u²/2")
      )
    }
    val n = r + 1
    val xPow = when {
      n == 0.5 -> "√x"
      n == Math.rint(n) -> "x${MathFormat.sup(n.toInt())}"
      else -> "x^(${MathFormat.num(n)})"
    }
    val inner = MathFormat.joinSigned(listOf(MathFormat.coefTimes(1 / n, "ln(x)"), MathFormat.num(-1 / (n * n))))
    val pretty = MathFormat.coefTimes(k, "$xPow($inner)")
    val v = MathFormat.coefTimes(1 / n, xPow)
    return Result(
      "${MathFormat.plainNumber(k)}*x^${MathFormat.plainNumber(n)}*(ln(x)/${MathFormat.plainNumber(n)}-${MathFormat.plainNumber(1 / (n * n))})",
      pretty,
      listOf(
        "Integrate by parts with u = ln(x), dv = x^(${MathFormat.num(r)}) dx ⇒ du = dx/x, v = $v",
        "Result: $pretty + C"
      )
    )
  }

  private fun tanRule(c: Double, lin: Lin): Result {
    val k = -c / lin.a
    return Result(
      "${MathFormat.plainNumber(k)}*ln(abs(cos(${lin.plain})))",
      MathFormat.coefTimes(k, "ln|cos(${lin.pretty})|"),
      listOf("Write tan(u) = sin(u)/cos(u) and substitute w = cos(u): ∫ tan(u) du = -ln|cos(u)|")
    )
  }

  private fun powerRule(c: Double, lin: Lin, r: Double): Result {
    if (abs(r + 1) < 1e-12) {
      val k = c / lin.a
      return Result(
        "${MathFormat.plainNumber(k)}*ln(abs(${lin.plain}))",
        MathFormat.coefTimes(k, "ln|${lin.pretty}|"),
        listOf("Use ∫ 1/u du = ln|u|" + if (lin.isX) "" else " with u = ${lin.pretty}")
      )
    }
    val n = r + 1
    val k = c / (n * lin.a)
    val powPretty = when {
      n == 0.5 -> if (lin.isX) "√x" else "√(${lin.pretty})"
      n == Math.rint(n) -> lin.wrapped + MathFormat.sup(n.toInt())
      else -> lin.wrapped + "^(${MathFormat.num(n)})"
    }
    val pretty = MathFormat.coefTimes(k, powPretty)
    return Result(
      "${MathFormat.plainNumber(k)}*${lin.plain}^${MathFormat.plainNumber(n)}",
      pretty,
      listOf(
        "Apply the power rule ∫ uⁿ du = uⁿ⁺¹/(n+1) with n = ${MathFormat.num(r)}" + if (lin.isX) "" else " and u = ${lin.pretty}",
        "Result: $pretty + C"
      )
    )
  }

  private fun rationalRule(p: DoubleArray, q: DoubleArray): Result? {
    val dq = PolyFit.degree(q)
    if (dq == 0) return polyResult(scale(p, 1.0 / q[0]))
    if (dq > 2) return null
    val (quot, rem) = PolyFit.divide(p, q)
    val parts = ArrayList<Result>()
    if (quot.any { abs(it) > 1e-12 }) parts.add(polyResult(quot))
    val steps = ArrayList<String>()
    if (PolyFit.degree(p) >= dq) steps.add("Divide the polynomials: quotient ${MathFormat.poly(quot)}, remainder ${MathFormat.poly(rem)}")

    if (dq == 1) {
      val lin = Lin(q[1], q[0])
      val r = rem[0]
      if (r != 0.0) parts.add(powerRule(r, lin, -1.0))
    } else {
      val qa = q[2]; val qb = q[1]; val qc = q[0]
      val m = rem.getOrElse(1) { 0.0 }
      val n = rem[0]
      val disc = qb * qb - 4 * qa * qc
      val qPretty = MathFormat.poly(q)
      if (disc > 1e-12 && (abs(m) > 1e-12 || abs(n) > 1e-12)) {
        // True partial fraction decomposition over distinct real roots r1, r2:
        // (m*x + n) / (qa*(x - r1)*(x - r2)) = A/(x - r1) + B/(x - r2)
        val s = sqrt(disc)
        val r1 = (-qb - s) / (2 * qa)
        val r2 = (-qb + s) / (2 * qa)
        val aCoef = (m * r1 + n) / (qa * (r1 - r2))
        val bCoef = (m * r2 + n) / (qa * (r2 - r1))
        val l1 = Lin(1.0, -r1)
        val l2 = Lin(1.0, -r2)
        val remPretty = MathFormat.poly(doubleArrayOf(n, m))
        val pfSteps = listOf(
          "Factor the denominator: $qPretty = ${if (abs(qa - 1.0) < 1e-12) "" else "${MathFormat.num(qa)}·"}(${l1.pretty})(${l2.pretty})",
          "Decompose into partial fractions: ($remPretty)/($qPretty) = A/(${l1.pretty}) + B/(${l2.pretty}) with A = ${MathFormat.num(aCoef)}, B = ${MathFormat.num(bCoef)}",
          "Integrate each fraction using ∫ du/u = ln|u|"
        )
        if (abs(aCoef + bCoef) < 1e-12) {
          parts.add(Result(
            "${MathFormat.plainNumber(aCoef)}*(ln(abs(${l1.plain}))-ln(abs(${l2.plain})))",
            MathFormat.scaled(aCoef, "ln|${l1.pretty}| - ln|${l2.pretty}|"),
            pfSteps
          ))
        } else {
          val exprTerms = ArrayList<String>()
          val prettyTerms = ArrayList<String>()
          if (abs(aCoef) > 1e-12) {
            exprTerms += "${MathFormat.plainNumber(aCoef)}*ln(abs(${l1.plain}))"
            prettyTerms += MathFormat.coefTimes(aCoef, "ln|${l1.pretty}|")
          }
          if (abs(bCoef) > 1e-12) {
            exprTerms += "${MathFormat.plainNumber(bCoef)}*ln(abs(${l2.plain}))"
            prettyTerms += MathFormat.coefTimes(bCoef, "ln|${l2.pretty}|")
          }
          parts.add(Result(
            exprTerms.joinToString("+"),
            MathFormat.joinSigned(prettyTerms),
            pfSteps
          ))
        }
      } else {
        // m x + n = (m / 2qa)(2qa x + qb) + (n - m qb / 2qa)
        val lnCoef = m / (2 * qa)
        val c2 = n - m * qb / (2 * qa)
        if (lnCoef != 0.0) {
          parts.add(Result("${MathFormat.plainNumber(lnCoef)}*ln(abs(${MathFormat.polyPlain(q)}))",
            MathFormat.coefTimes(lnCoef, "ln|$qPretty|"),
            listOf("The numerator contains the derivative of the denominator: ∫ q'/q dx = ln|q|")))
        }
        if (c2 != 0.0) {
          when {
            disc < -1e-12 -> {
              val s = sqrt(-disc)
              val arg = Lin(2 * qa / s, qb / s)
              val k = 2 * c2 / s
              parts.add(Result("${MathFormat.plainNumber(k)}*arctan(${arg.plain})",
                MathFormat.coefTimes(k, "arctan(${arg.pretty})"),
                listOf("Complete the square in $qPretty and use ∫ du/(1 + u²) = arctan(u)")))
            }
            else -> {
              val root = -qb / (2 * qa)
              parts.add(powerRule(c2 / qa, Lin(1.0, -root), -2.0))
            }
          }
        }
      }
    }
    if (parts.isEmpty()) return null
    val pretty = MathFormat.joinSigned(parts.map { it.pretty })
    return Result(parts.joinToString("+") { "(${it.expr})" }, pretty, steps + parts.flatMap { it.steps } + "Result: $pretty + C")
  }
}
