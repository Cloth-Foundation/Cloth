package cloth.backend

import java.math.BigInteger

/** Feasibility prototype, not a compiler runtime: bounded code for full-cap values. */
internal object IntegerLimbProbeIR {

	fun source(bits: Int, wordBits: Int): String {
		require(bits in 1..131072 && wordBits in setOf(32, 64))
		val count = (bits + wordBits - 1) / wordBits
		val word = "i$wordBits"
		val array = "[$count x $word]"
		val wordMask = BigInteger.ONE.shiftLeft(wordBits) - BigInteger.ONE
		val mask = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE
		val topMask = BigInteger.ONE.shiftLeft(bits - (count - 1) * wordBits) - BigInteger.ONE
		fun words(value: BigInteger) = (0 until count).joinToString(", ") { "$word ${value.shiftRight(it * wordBits).and(wordMask)}" }
		val cases = listOf((mask.shiftRight(1)) to BigInteger.ONE, mask to BigInteger.ONE, mask.divide(3.toBigInteger()) to mask.divide(5.toBigInteger()))
		val globals = buildString {
			append("@out = global $array zeroinitializer\n@tuple = global { i8, $array } zeroinitializer\n")
			cases.forEachIndexed { index, (a, b) ->
				append("@a$index = global $array [${words(a)}]\n@b$index = global $array [${words(b)}]\n@e$index = global $array [${words((a + b).and(mask))}]\n")
			}
		}
		return """
$globals
define void @limb_add(ptr %out, ptr %ap, ptr %bp) #0 {
entry:
  br label %loop
loop:
  %index = phi $word [ 0, %entry ], [ %next, %loop ]
  %carry = phi $word [ 0, %entry ], [ %next_carry, %loop ]
  %a_ptr = getelementptr $word, ptr %ap, $word %index
  %b_ptr = getelementptr $word, ptr %bp, $word %index
  %out_ptr = getelementptr $word, ptr %out, $word %index
  %a = load volatile $word, ptr %a_ptr
  %b = load volatile $word, ptr %b_ptr
  %partial = add $word %a, %b
  %carry_a = icmp ult $word %partial, %a
  %sum = add $word %partial, %carry
  %carry_b = icmp ult $word %sum, %partial
  %carry_bit = or i1 %carry_a, %carry_b
  %next_carry = zext i1 %carry_bit to $word
  %last = icmp eq $word %index, ${count - 1}
  %masked = and $word %sum, $topMask
  %value = select i1 %last, $word %masked, $word %sum
  store $word %value, ptr %out_ptr
  %next = add $word %index, 1
  %more = icmp ult $word %next, $count
  br i1 %more, label %loop, label %exit
exit:
  ret void
}
define void @copy_recursive(ptr %out, ptr %source, i32 %depth) #0 {
entry:
  %done = icmp eq i32 %depth, 0
  br i1 %done, label %base, label %recurse
recurse:
  %next_depth = sub i32 %depth, 1
  call void @copy_recursive(ptr %out, ptr %source, i32 %next_depth)
  ret void
base:
  br label %loop
loop:
  %index = phi $word [ 0, %base ], [ %next, %loop ]
  %source_ptr = getelementptr $word, ptr %source, $word %index
  %out_ptr = getelementptr $word, ptr %out, $word %index
  %value = load volatile $word, ptr %source_ptr
  store $word %value, ptr %out_ptr
  %next = add $word %index, 1
  %more = icmp ult $word %next, $count
  br i1 %more, label %loop, label %exit
exit:
  ret void
}
define i1 @test(ptr %ap, ptr %bp, ptr %expected) #0 {
entry:
  call void @limb_add(ptr @out, ptr %ap, ptr %bp)
  %payload = getelementptr { i8, $array }, ptr @tuple, i32 0, i32 1
  store i8 1, ptr @tuple
  call void @copy_recursive(ptr %payload, ptr @out, i32 2)
  %presence = load volatile i8, ptr @tuple
  %present = icmp eq i8 %presence, 1
  br label %loop
loop:
  %index = phi $word [ 0, %entry ], [ %next, %loop ]
  %ok = phi i1 [ %present, %entry ], [ %both, %loop ]
  %result_ptr = getelementptr $word, ptr %payload, $word %index
  %expected_ptr = getelementptr $word, ptr %expected, $word %index
  %actual = load volatile $word, ptr %result_ptr
  %wanted = load volatile $word, ptr %expected_ptr
  %equal = icmp eq $word %actual, %wanted
  %both = and i1 %ok, %equal
  %next = add $word %index, 1
  %more = icmp ult $word %next, $count
  br i1 %more, label %loop, label %exit
exit:
  ret i1 %both
}
define i32 @main() {
entry:
  %a = call i1 @test(ptr @a0, ptr @b0, ptr @e0)
  %b = call i1 @test(ptr @a1, ptr @b1, ptr @e1)
  %c = call i1 @test(ptr @a2, ptr @b2, ptr @e2)
  %ab = and i1 %a, %b
  %all = and i1 %ab, %c
  %failed = xor i1 %all, true
  %status = zext i1 %failed to i32
  ret i32 %status
}
attributes #0 = { noinline }
""".trimIndent() + "\n"
	}
}
