package cloth.backend

import java.math.BigInteger

/** Raw LLVM experiments deliberately bypass Cloth's current source-width validation. */
internal object IntegerProbeIR {

	val widths = listOf(1, 3, 7, 12, 24, 63, 64, 65, 127, 128, 129, 257, 1024, 4096, 65535, 65536, 131072)
	val groups = listOf("core", "mul", "div", "fp", "half", "fp-mixed", "calls", "indirect", "limbs")
	val triples = listOf("i686-pc-windows-msvc", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc", "x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "x86_64-apple-darwin", "aarch64-apple-darwin")
	private val one = BigInteger.ONE
	fun source(bits: Int, group: String, wordBits: Int = 64): String {
		require(bits in 1..131072 && group in groups)
		if (group == "limbs") return IntegerLimbProbeIR.source(bits, wordBits)
		val mask = one.shiftLeft(bits) - one
		fun low(value: BigInteger) = value.and(mask)
		fun signed(value: BigInteger) = if (value.testBit(bits - 1)) value - one.shiftLeft(bits) else value
		val helpers = StringBuilder()
		val globals = StringBuilder("@out = global i$bits 0\n")
		val checks = mutableListOf<String>()
		val driver = StringBuilder("define i32 @main() {\nentry:\n")
		var serial = 0
		fun check(name: String, a: BigInteger, b: BigInteger, expected: BigInteger) {
			val index = serial++
			globals.append("@a$index = global i$bits ${low(a)}\n@b$index = global i$bits ${low(b)}\n")
			driver.append("  call void @$name(ptr @out, ptr @a$index, ptr @b$index)\n  %v$index = load volatile i$bits, ptr @out\n  %ok$index = icmp eq i$bits %v$index, ${low(expected)}\n")
			checks.add("%ok$index")
		}

		val positive = if (bits < 4) one.shiftLeft(bits - 1) - one else one.shiftLeft(bits - 2) + 3.toBigInteger()
		val negative = low(-positive - one)
		val divisor = if (bits < 3) one else 3.toBigInteger()
		val pairs = listOf(positive to divisor, negative to divisor, mask to one)
		fun operation(name: String, expression: String, oracle: (BigInteger, BigInteger) -> BigInteger, inputs: List<Pair<BigInteger, BigInteger>> = pairs) {
			helpers.append("define void @$name(ptr %out, ptr %ap, ptr %bp) #0 {\nentry:\n  %a = load volatile i$bits, ptr %ap\n  %b = load volatile i$bits, ptr %bp\n$expression\n  store i$bits %result, ptr %out\n  ret void\n}\n")
			inputs.forEach { (a, b) -> check(name, a, b, oracle(a, b)) }
		}

		fun boolResult(value: String) = if (bits == 1) "  %result = or i1 $value, false" else "  %result = zext i1 $value to i$bits"
		when (group) {
			"core"                   -> {
				operation("add", "  %result = add i$bits %a, %b", { a, b -> a + b })
				operation("sub", "  %result = sub i$bits %a, %b", { a, b -> a - b })
				operation("neg", "  %result = sub i$bits 0, %a", { a, _ -> -a })
				operation("ult", "  %flag = icmp ult i$bits %a, %b\n${boolResult("%flag")}", { a, b -> if (a < b) one else BigInteger.ZERO })
				operation("slt", "  %flag = icmp slt i$bits %a, %b\n${boolResult("%flag")}", { a, b -> if (signed(a) < signed(b)) one else BigInteger.ZERO })
				operation("select_phi", """
  %flag = icmp slt i$bits %a, %b
  %selected = select i1 %flag, i$bits %a, i$bits %b
  %low_bit = and i$bits %a, 1
  %odd = icmp ne i$bits %low_bit, 0
  br i1 %odd, label %yes, label %no
yes:
  br label %merge
no:
  br label %merge
merge:
  %result = phi i$bits [ %selected, %yes ], [ %a, %no ]
""".trimEnd(), { a, b -> if (a.testBit(0) && signed(b) < signed(a)) b else a })
				if (bits > 1) {
					val narrow = maxOf(1, bits / 2)
					val narrowMask = one.shiftLeft(narrow) - one
					operation("narrow_unsigned", "  %small = trunc i$bits %a to i$narrow\n  %result = zext i$narrow %small to i$bits", { a, _ -> a.and(narrowMask) })
					operation("narrow_signed", "  %small = trunc i$bits %a to i$narrow\n  %result = sext i$narrow %small to i$bits", { a, _ -> val small = a.and(narrowMask); if (small.testBit(narrow - 1)) small - one.shiftLeft(narrow) else small })
				}
			}

			"mul"                    -> operation("mul", "  %result = mul i$bits %a, %b", { a, b -> a * b })
			"div"                    -> {
				operation("udiv", "  %result = udiv i$bits %a, %b", { a, b -> a / b })
				operation("urem", "  %result = urem i$bits %a, %b", { a, b -> a % b })
				// These raw instructions require valid inputs; Cloth's guard integration is Stage 3.
				val safe = if (bits == 1) listOf(BigInteger.ZERO to one) else pairs
				operation("sdiv", "  %result = sdiv i$bits %a, %b", { a, b -> signed(a) / signed(b) }, safe)
				operation("srem", "  %result = srem i$bits %a, %b", { a, b -> signed(a) % signed(b) }, safe)
			}

			"fp", "half", "fp-mixed" -> for (format in when (group) {
				"half" -> listOf("half"); "fp-mixed" -> listOf("half", "float", "double"); else -> listOf("float", "double")
			}) for (unsigned in listOf(false, true)) {
				val direction = if (unsigned) "u" else "s"
				val inputs = if (bits == 1 && !unsigned) listOf(BigInteger.ZERO to one) else listOf(one to one)
				operation("${format}_$direction", "  %floating = ${direction}itofp i$bits %a to $format\n  %result = fpto${direction}i $format %floating to i$bits", { a, _ -> a }, inputs)
			}

			"calls"                  -> {
				helpers.append("""
define i$bits @recursive(i$bits %value, i32 %depth) #0 {
entry:
  %done = icmp eq i32 %depth, 0
  br i1 %done, label %base, label %recurse
base:
  ret i$bits %value
recurse:
  %next = sub i32 %depth, 1
  %v = call i$bits @recursive(i$bits %value, i32 %next)
  ret i$bits %v
}
define { i1, i$bits, i$bits } @aggregate(i$bits %a, i$bits %b) #0 {
entry:
  %x = insertvalue { i1, i$bits, i$bits } poison, i1 true, 0
  %y = insertvalue { i1, i$bits, i$bits } %x, i$bits %a, 1
  %z = insertvalue { i1, i$bits, i$bits } %y, i$bits %b, 2
  ret { i1, i$bits, i$bits } %z
}
""")
				operation("calls", """
  %v = call i$bits @recursive(i$bits %a, i32 2)
  %tuple = call { i1, i$bits, i$bits } @aggregate(i$bits %v, i$bits %b)
  %present = extractvalue { i1, i$bits, i$bits } %tuple, 0
  %first = extractvalue { i1, i$bits, i$bits } %tuple, 1
  %second = extractvalue { i1, i$bits, i$bits } %tuple, 2
  %both = add i$bits %first, %second
  %result = select i1 %present, i$bits %both, i$bits 0
""".trimEnd(), { a, b -> a + b })
			}

			"indirect"               -> {
				helpers.append("""
define void @recursive(ptr %out, ptr %source, i32 %depth) #0 {
entry:
  %done = icmp eq i32 %depth, 0
  br i1 %done, label %base, label %recurse
base:
  %value = load volatile i$bits, ptr %source
  store i$bits %value, ptr %out
  ret void
recurse:
  %next = sub i32 %depth, 1
  call void @recursive(ptr %out, ptr %source, i32 %next)
  ret void
}
""")
				operation("indirect", """
  %tuple = alloca { i8, i$bits, i$bits }
  %first = getelementptr { i8, i$bits, i$bits }, ptr %tuple, i32 0, i32 1
  %second = getelementptr { i8, i$bits, i$bits }, ptr %tuple, i32 0, i32 2
  store i8 1, ptr %tuple
  call void @recursive(ptr %first, ptr %ap, i32 2)
  store i$bits %b, ptr %second
  %copied = load volatile i$bits, ptr %first
  %other = load volatile i$bits, ptr %second
  %result = add i$bits %copied, %other
""".trimEnd(), { a, b -> a + b })
			}
		}
		var combined = checks.first()
		checks.drop(1).forEachIndexed { index, check ->
			driver.append("  %all$index = and i1 $combined, $check\n")
			combined = "%all$index"
		}
		driver.append("  %failed = xor i1 $combined, true\n  %status = zext i1 %failed to i32\n  ret i32 %status\n}\n")
		return "$globals\n$helpers\n$driver\nattributes #0 = { noinline }\n"
	}
}
