package cloth.emitter.types

/** IEEE conversion helpers are separate from the integer primitives. */
internal object IntegerFloatKernelIR {

	val source = """
define internal i64 @cloth.integer.extract(ptr %a, i32 %start, i32 %count) noinline {
entry:
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %loop ]
  %result = phi i64 [ 0, %entry ], [ %combined, %loop ]
  %index = add i32 %start, %i
  %bit = call i1 @cloth.integer.bit(ptr %a, i32 %index)
  %value = zext i1 %bit to i64
  %offset = zext i32 %i to i64
  %shifted = shl i64 %value, %offset
  %combined = or i64 %result, %shifted
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %count
  br i1 %more, label %loop, label %end
end:
  ret i64 %combined
}
define internal i64 @cloth.integer.tofp(ptr %a, i32 %bits, i1 %signed, i32 %precision, i32 %bias) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %magnitude = alloca i32, i32 %n
  %signindex = sub i32 %bits, 1
  %signbit = call i1 @cloth.integer.bit(ptr %a, i32 %signindex)
  %negative = and i1 %signed, %signbit
  br i1 %negative, label %negate, label %positive
negate:
  call void @cloth.integer.neg(ptr %magnitude, ptr %a, i32 %bits)
  br label %measure
positive:
  call void @cloth.integer.copy(ptr %magnitude, ptr %a, i32 %bits)
  br label %measure
measure:
  %length = call i32 @cloth.integer.length(ptr %magnitude, i32 %bits)
  %empty = icmp eq i32 %length, 0
  br i1 %empty, label %zero, label %extract
zero:
  ret i64 0
extract:
  %large = icmp ugt i32 %length, %precision
  %count = select i1 %large, i32 %precision, i32 %length
  %start = sub i32 %length, %count
  %significand = call i64 @cloth.integer.extract(ptr %magnitude, i32 %start, i32 %count)
  %left0 = sub i32 %precision, %count
  %left = zext i32 %left0 to i64
  %aligned = shl i64 %significand, %left
  br i1 %large, label %round, label %combine
round:
  %guardindex = sub i32 %start, 1
  %guard = call i1 @cloth.integer.bit(ptr %magnitude, i32 %guardindex)
  %sticky = call i1 @cloth.integer.anylow(ptr %magnitude, i32 %guardindex)
  %odd = trunc i64 %aligned to i1
  %tieup = or i1 %sticky, %odd
  %increment = and i1 %guard, %tieup
  br label %combine
combine:
  %roundup = phi i1 [ false, %extract ], [ %increment, %round ]
  %add = zext i1 %roundup to i64
  %rounded = add i64 %aligned, %add
  %p = zext i32 %precision to i64
  %limit = shl i64 1, %p
  %carry = icmp eq i64 %rounded, %limit
  %half = lshr i64 %rounded, 1
  %finalsig = select i1 %carry, i64 %half, i64 %rounded
  %carryvalue = zext i1 %carry to i32
  %exponent0 = sub i32 %length, 1
  %exponent = add i32 %exponent0, %carryvalue
  %biased = add i32 %exponent, %bias
  %double = icmp eq i32 %precision, 53
  %maximum = select i1 %double, i32 2047, i32 255
  %infinite = icmp uge i32 %biased, %maximum
  %encoded = select i1 %infinite, i32 %maximum, i32 %biased
  %fractionbits0 = sub i32 %precision, 1
  %fractionbits = zext i32 %fractionbits0 to i64
  %encoded64 = zext i32 %encoded to i64
  %expbits = shl i64 %encoded64, %fractionbits
  %fractionlimit = shl i64 1, %fractionbits
  %fractionmask = sub i64 %fractionlimit, 1
  %fraction0 = and i64 %finalsig, %fractionmask
  %fraction = select i1 %infinite, i64 0, i64 %fraction0
  %unsignedbits = or i64 %expbits, %fraction
  %signpos = select i1 %double, i64 63, i64 31
  %signvalue = zext i1 %negative to i64
  %sign = shl i64 %signvalue, %signpos
  %result = or i64 %unsignedbits, %sign
  ret i64 %result
}
define internal i32 @cloth.integer.fromfp(ptr %out, i64 %raw, i32 %bits, i1 %signed) noinline {
entry:
  %shifted = lshr i64 %raw, 52
  %exponent = and i64 %shifted, 2047
  %invalid = icmp eq i64 %exponent, 2047
  br i1 %invalid, label %bad, label %finite
finite:
  %small = icmp ult i64 %exponent, 1023
  br i1 %small, label %zero, label %bounds
zero:
  call void @cloth.integer.zero(ptr %out, i32 %bits)
  ret i32 0
bounds:
  %negative = icmp slt i64 %raw, 0
  %unsigned = xor i1 %signed, true
  %negativeunsigned = and i1 %negative, %unsigned
  %exponent0 = sub i64 %exponent, 1023
  %e = trunc i64 %exponent0 to i32
  %signindex = sub i32 %bits, 1
  %limit = select i1 %signed, i32 %signindex, i32 %bits
  %negfits = icmp ule i32 %e, %limit
  %posfits = icmp ult i32 %e, %limit
  %fits = select i1 %negative, i1 %negfits, i1 %posfits
  %notfits = xor i1 %fits, true
  %badbounds = or i1 %negativeunsigned, %notfits
  br i1 %badbounds, label %bad, label %setup
setup:
  call void @cloth.integer.zero(ptr %out, i32 %bits)
  %fraction = and i64 %raw, 4503599627370495
  %significand = or i64 %fraction, 4503599627370496
  %base = sub i32 %e, 52
  br label %loop
loop:
  %index = phi i32 [ 0, %setup ], [ %next, %advance ]
  %offset = zext i32 %index to i64
  %part = lshr i64 %significand, %offset
  %bit = trunc i64 %part to i1
  %destination = add i32 %base, %index
  %nonnegative = icmp sge i32 %destination, 0
  %within = icmp ult i32 %destination, %bits
  %positionvalid = and i1 %nonnegative, %within
  %setbit = and i1 %positionvalid, %bit
  br i1 %setbit, label %store, label %advance
store:
  %wordindex = lshr i32 %destination, 5
  %wordoffset = and i32 %destination, 31
  %p = getelementptr i32, ptr %out, i32 %wordindex
  %v = load i32, ptr %p
  %mask = shl i32 1, %wordoffset
  %combined = or i32 %v, %mask
  store i32 %combined, ptr %p
  br label %advance
advance:
  %next = add i32 %index, 1
  %more = icmp ult i32 %next, 53
  br i1 %more, label %loop, label %minimumcheck
minimumcheck:
  %boundary = icmp eq i32 %e, %signindex
  %checkmin = and i1 %negative, %boundary
  br i1 %checkmin, label %minimum, label %sign
minimum:
  %lower = call i1 @cloth.integer.anylow(ptr %out, i32 %signindex)
  br i1 %lower, label %bad, label %sign
sign:
  br i1 %negative, label %negate, label %done
negate:
  call void @cloth.integer.neg(ptr %out, ptr %out, i32 %bits)
  br label %done
done:
  ret i32 0
bad:
  ret i32 4
}
""".trimIndent()
}
