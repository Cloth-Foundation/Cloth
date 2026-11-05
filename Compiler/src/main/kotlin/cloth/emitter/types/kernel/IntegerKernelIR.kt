package cloth.emitter.types.kernel

/** Private bounded-loop arithmetic. Words are little-endian i32 chunks of native limb storage.
 * Inputs are canonical, destinations disjoint except for copy/add/negate, and 1 <= bits <= 131072.
 * Runtime statuses: 0 success, 1 zero divisor, 2 minimum/-1, 3 negative exponent, 4 invalid cast.
 */
internal object IntegerKernelIR {

	val source: String = """
declare i32 @llvm.ctlz.i32(i32, i1 immarg)

define internal i32 @cloth.integer.words(i32 %bits) alwaysinline {
  %sum = add i32 %bits, 31
  %n = lshr i32 %sum, 5
  ret i32 %n
}
define internal i1 @cloth.integer.bit(ptr %a, i32 %index) alwaysinline {
  %word = lshr i32 %index, 5
  %offset = and i32 %index, 31
  %p = getelementptr i32, ptr %a, i32 %word
  %v = load i32, ptr %p
  %shifted = lshr i32 %v, %offset
  %bit = trunc i32 %shifted to i1
  ret i1 %bit
}
define internal void @cloth.integer.mask(ptr %out, i32 %bits) alwaysinline {
  %n = call i32 @cloth.integer.words(i32 %bits)
  %last = sub i32 %n, 1
  %minus = sub i32 %bits, 1
  %used0 = and i32 %minus, 31
  %shift = sub i32 31, %used0
  %mask = lshr i32 -1, %shift
  %p = getelementptr i32, ptr %out, i32 %last
  %v = load i32, ptr %p
  %masked = and i32 %v, %mask
  store i32 %masked, ptr %p
  ret void
}
define internal void @cloth.integer.zero(ptr %out, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %loop ]
  %p = getelementptr i32, ptr %out, i32 %i
  store i32 0, ptr %p
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %loop, label %end
end:
  ret void
}
define internal void @cloth.integer.copy(ptr %out, ptr %a, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %loop ]
  %ap = getelementptr i32, ptr %a, i32 %i
  %op = getelementptr i32, ptr %out, i32 %i
  %v = load i32, ptr %ap
  store i32 %v, ptr %op
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %loop, label %end
end:
  ret void
}
define internal i32 @cloth.integer.length(ptr %a, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  br label %loop
loop:
  %remaining = phi i32 [ %n, %entry ], [ %index, %again ]
  %index = sub i32 %remaining, 1
  %p = getelementptr i32, ptr %a, i32 %index
  %v = load i32, ptr %p
  %nonzero = icmp ne i32 %v, 0
  br i1 %nonzero, label %found, label %again
again:
  %more = icmp ne i32 %index, 0
  br i1 %more, label %loop, label %empty
found:
  %zeros = call i32 @llvm.ctlz.i32(i32 %v, i1 false)
  %base = mul i32 %remaining, 32
  %length = sub i32 %base, %zeros
  ret i32 %length
empty:
  ret i32 0
}
define internal i1 @cloth.integer.anylow(ptr %a, i32 %limit) noinline {
entry:
  %empty = icmp eq i32 %limit, 0
  br i1 %empty, label %no, label %loop
loop:
  %index = phi i32 [ 0, %entry ], [ %next, %again ]
  %remaining = phi i32 [ %limit, %entry ], [ %rest, %again ]
  %p = getelementptr i32, ptr %a, i32 %index
  %v = load i32, ptr %p
  %full = icmp uge i32 %remaining, 32
  %used = select i1 %full, i32 32, i32 %remaining
  %shift = sub i32 32, %used
  %mask = lshr i32 -1, %shift
  %part = and i32 %v, %mask
  %nonzero = icmp ne i32 %part, 0
  br i1 %nonzero, label %yes, label %again
again:
  %next = add i32 %index, 1
  %rest = sub i32 %remaining, %used
  %more = icmp ne i32 %rest, 0
  br i1 %more, label %loop, label %no
yes:
  ret i1 true
no:
  ret i1 false
}
define internal void @cloth.integer.cast(ptr %out, ptr %a, i32 %from, i32 %to, i1 %signed) noinline {
entry:
  %sn = call i32 @cloth.integer.words(i32 %from)
  %tn = call i32 @cloth.integer.words(i32 %to)
  %signindex = sub i32 %from, 1
  %signbit = call i1 @cloth.integer.bit(ptr %a, i32 %signindex)
  %negative = and i1 %signed, %signbit
  %fill = select i1 %negative, i32 -1, i32 0
  %last = sub i32 %sn, 1
  %tail = and i32 %signindex, 31
  %shift = sub i32 31, %tail
  %mask = lshr i32 -1, %shift
  %high = xor i32 %mask, -1
  %extension = and i32 %high, %fill
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %store ]
  %exists = icmp ult i32 %i, %sn
  br i1 %exists, label %read, label %pad
read:
  %ap = getelementptr i32, ptr %a, i32 %i
  %v = load i32, ptr %ap
  %islast = icmp eq i32 %i, %last
  %ext = select i1 %islast, i32 %extension, i32 0
  %extended = or i32 %v, %ext
  br label %store
pad:
  br label %store
store:
  %value = phi i32 [ %extended, %read ], [ %fill, %pad ]
  %op = getelementptr i32, ptr %out, i32 %i
  store i32 %value, ptr %op
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %tn
  br i1 %more, label %loop, label %end
end:
  call void @cloth.integer.mask(ptr %out, i32 %to)
  ret void
}
define internal void @cloth.integer.neg(ptr %out, ptr %a, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %loop ]
  %carry = phi i64 [ 1, %entry ], [ %carryout, %loop ]
  %ap = getelementptr i32, ptr %a, i32 %i
  %op = getelementptr i32, ptr %out, i32 %i
  %v = load i32, ptr %ap
  %inverse = xor i32 %v, -1
  %wide = zext i32 %inverse to i64
  %sum = add i64 %wide, %carry
  %low = trunc i64 %sum to i32
  store i32 %low, ptr %op
  %carryout = lshr i64 %sum, 32
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %loop, label %end
end:
  call void @cloth.integer.mask(ptr %out, i32 %bits)
  ret void
}
define internal void @cloth.integer.addsub(ptr %out, ptr %a, ptr %b, i32 %bits, i1 %subtract) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %initial = zext i1 %subtract to i64
  %inverse = select i1 %subtract, i32 -1, i32 0
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %loop ]
  %carry = phi i64 [ %initial, %entry ], [ %carryout, %loop ]
  %ap = getelementptr i32, ptr %a, i32 %i
  %bp = getelementptr i32, ptr %b, i32 %i
  %op = getelementptr i32, ptr %out, i32 %i
  %av = load i32, ptr %ap
  %bv = load i32, ptr %bp
  %bb = xor i32 %bv, %inverse
  %aw = zext i32 %av to i64
  %bw = zext i32 %bb to i64
  %ab = add i64 %aw, %bw
  %sum = add i64 %ab, %carry
  %low = trunc i64 %sum to i32
  store i32 %low, ptr %op
  %carryout = lshr i64 %sum, 32
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %loop, label %end
end:
  call void @cloth.integer.mask(ptr %out, i32 %bits)
  ret void
}
define internal i32 @cloth.integer.compare(ptr %a, ptr %b, i32 %bits, i1 %signed) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %signindex = sub i32 %bits, 1
  %as = call i1 @cloth.integer.bit(ptr %a, i32 %signindex)
  %bs = call i1 @cloth.integer.bit(ptr %b, i32 %signindex)
  %different = xor i1 %as, %bs
  %split = and i1 %signed, %different
  br i1 %split, label %sign, label %loop
sign:
  %signedresult = select i1 %as, i32 -1, i32 1
  ret i32 %signedresult
loop:
  %remaining = phi i32 [ %n, %entry ], [ %i, %again ]
  %i = sub i32 %remaining, 1
  %ap = getelementptr i32, ptr %a, i32 %i
  %bp = getelementptr i32, ptr %b, i32 %i
  %av = load i32, ptr %ap
  %bv = load i32, ptr %bp
  %equal = icmp eq i32 %av, %bv
  br i1 %equal, label %again, label %found
found:
  %less = icmp ult i32 %av, %bv
  %result = select i1 %less, i32 -1, i32 1
  ret i32 %result
again:
  %more = icmp ne i32 %i, 0
  br i1 %more, label %loop, label %same
same:
  ret i32 0
}
define internal void @cloth.integer.multiply(ptr %out, ptr %a, ptr %b, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  call void @cloth.integer.zero(ptr %out, i32 %bits)
  br label %outer
outer:
  %i = phi i32 [ 0, %entry ], [ %inext, %advance ]
  %ap = getelementptr i32, ptr %a, i32 %i
  %av = load i32, ptr %ap
  %aw = zext i32 %av to i64
  %nonzero = icmp ne i32 %av, 0
  br i1 %nonzero, label %inner, label %advance
inner:
  %j = phi i32 [ 0, %outer ], [ %jnext, %inner ]
  %carry = phi i64 [ 0, %outer ], [ %carryout, %inner ]
  %index = add i32 %i, %j
  %bp = getelementptr i32, ptr %b, i32 %j
  %op = getelementptr i32, ptr %out, i32 %index
  %bv = load i32, ptr %bp
  %ov = load i32, ptr %op
  %bw = zext i32 %bv to i64
  %ow = zext i32 %ov to i64
  %product = mul i64 %aw, %bw
  %partial = add i64 %product, %ow
  %sum = add i64 %partial, %carry
  %low = trunc i64 %sum to i32
  store i32 %low, ptr %op
  %carryout = lshr i64 %sum, 32
  %jnext = add i32 %j, 1
  %nextindex = add i32 %i, %jnext
  %moreinner = icmp ult i32 %nextindex, %n
  br i1 %moreinner, label %inner, label %advance
advance:
  %inext = add i32 %i, 1
  %more = icmp ult i32 %inext, %n
  br i1 %more, label %outer, label %end
end:
  call void @cloth.integer.mask(ptr %out, i32 %bits)
  ret void
}
define internal i1 @cloth.integer.shiftin(ptr %a, i32 %bits, i1 %incoming) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %carryin = zext i1 %incoming to i64
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %loop ]
  %carry = phi i64 [ %carryin, %entry ], [ %carryout, %loop ]
  %p = getelementptr i32, ptr %a, i32 %i
  %v = load i32, ptr %p
  %w = zext i32 %v to i64
  %shifted = shl i64 %w, 1
  %sum = or i64 %shifted, %carry
  %low = trunc i64 %sum to i32
  store i32 %low, ptr %p
  %carryout = lshr i64 %sum, 32
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %loop, label %end
end:
  %minus = sub i32 %bits, 1
  %tail = and i32 %minus, 31
  %used = add i32 %tail, 1
  %used64 = zext i32 %used to i64
  %overflowbits = lshr i64 %sum, %used64
  %overflow = icmp ne i64 %overflowbits, 0
  call void @cloth.integer.mask(ptr %a, i32 %bits)
  ret i1 %overflow
}
define internal void @cloth.integer.shl(ptr %out, ptr %a, i32 %bits, i32 %amount) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %wordshift = lshr i32 %amount, 5
  %offset = and i32 %amount, 31
  %up = zext i32 %offset to i64
  %down0 = sub i32 32, %offset
  %down = zext i32 %down0 to i64
  br label %loop
loop:
  %i = phi i32 [ 0, %entry ], [ %next, %store ]
  %available = icmp uge i32 %i, %wordshift
  br i1 %available, label %read, label %pad
read:
  %index = sub i32 %i, %wordshift
  %ap = getelementptr i32, ptr %a, i32 %index
  %v = load i32, ptr %ap
  %wide = zext i32 %v to i64
  %low = shl i64 %wide, %up
  %hasprevious = icmp ne i32 %index, 0
  br i1 %hasprevious, label %previous, label %combine
previous:
  %previousindex = sub i32 %index, 1
  %pp = getelementptr i32, ptr %a, i32 %previousindex
  %pv = load i32, ptr %pp
  %pw = zext i32 %pv to i64
  %high = lshr i64 %pw, %down
  br label %combine
combine:
  %extra = phi i64 [ 0, %read ], [ %high, %previous ]
  %combined = or i64 %low, %extra
  %part = trunc i64 %combined to i32
  br label %store
pad:
  br label %store
store:
  %value = phi i32 [ 0, %pad ], [ %part, %combine ]
  %op = getelementptr i32, ptr %out, i32 %i
  store i32 %value, ptr %op
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %loop, label %end
end:
  call void @cloth.integer.mask(ptr %out, i32 %bits)
  ret void
}
define internal void @cloth.integer.shrone(ptr %a, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  br label %loop
loop:
  %remaining = phi i32 [ %n, %entry ], [ %i, %loop ]
  %carry = phi i32 [ 0, %entry ], [ %nextcarry, %loop ]
  %i = sub i32 %remaining, 1
  %p = getelementptr i32, ptr %a, i32 %i
  %v = load i32, ptr %p
  %low = lshr i32 %v, 1
  %high = shl i32 %carry, 31
  %result = or i32 %low, %high
  store i32 %result, ptr %p
  %nextcarry = and i32 %v, 1
  %more = icmp ne i32 %i, 0
  br i1 %more, label %loop, label %end
end:
  ret void
}
define internal i32 @cloth.integer.divrem(ptr %q, ptr %r, ptr %a, ptr %b, i32 %bits, i1 %signed) noinline {
entry:
  %blen = call i32 @cloth.integer.length(ptr %b, i32 %bits)
  %zero = icmp eq i32 %blen, 0
  br i1 %zero, label %badzero, label %setup
badzero:
  ret i32 1
setup:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %aa = alloca i32, i32 %n
  %bb = alloca i32, i32 %n
  %signindex = sub i32 %bits, 1
  %sa = call i1 @cloth.integer.bit(ptr %a, i32 %signindex)
  %sb = call i1 @cloth.integer.bit(ptr %b, i32 %signindex)
  %aneg = and i1 %signed, %sa
  %bneg = and i1 %signed, %sb
  br i1 %aneg, label %negativea, label %positivea
negativea:
  call void @cloth.integer.neg(ptr %aa, ptr %a, i32 %bits)
  br label %prepareb
positivea:
  call void @cloth.integer.copy(ptr %aa, ptr %a, i32 %bits)
  br label %prepareb
prepareb:
  br i1 %bneg, label %negativeb, label %positiveb
negativeb:
  call void @cloth.integer.neg(ptr %bb, ptr %b, i32 %bits)
  br label %check
positiveb:
  call void @cloth.integer.copy(ptr %bb, ptr %b, i32 %bits)
  br label %check
check:
  %alen = call i32 @cloth.integer.length(ptr %aa, i32 %bits)
  %bmaglen = call i32 @cloth.integer.length(ptr %bb, i32 %bits)
  %atop = icmp eq i32 %alen, %bits
  %alow = call i1 @cloth.integer.anylow(ptr %aa, i32 %signindex)
  %noalow = xor i1 %alow, true
  %amin0 = and i1 %atop, %noalow
  %amin = and i1 %aneg, %amin0
  %bunit = icmp eq i32 %bmaglen, 1
  %bminusone = and i1 %bneg, %bunit
  %overflow = and i1 %amin, %bminusone
  br i1 %overflow, label %badoverflow, label %initialize
badoverflow:
  ret i32 2
initialize:
  call void @cloth.integer.zero(ptr %q, i32 %bits)
  call void @cloth.integer.zero(ptr %r, i32 %bits)
  br i1 %bunit, label %unit, label %general
unit:
  call void @cloth.integer.copy(ptr %q, ptr %aa, i32 %bits)
  br label %signs
general:
  %small = icmp ule i32 %bmaglen, 32
  br i1 %small, label %smallsetup, label %largesetup
smallsetup:
  %divisorword = load i32, ptr %bb
  %divisor = zext i32 %divisorword to i64
  br label %smallloop
smallloop:
  %smallremaining = phi i32 [ %n, %smallsetup ], [ %smallindex, %smallloop ]
  %carry = phi i64 [ 0, %smallsetup ], [ %remainder, %smallloop ]
  %smallindex = sub i32 %smallremaining, 1
  %smallap = getelementptr i32, ptr %aa, i32 %smallindex
  %smallqp = getelementptr i32, ptr %q, i32 %smallindex
  %smallav = load i32, ptr %smallap
  %smallaw = zext i32 %smallav to i64
  %upper = shl i64 %carry, 32
  %combined = or i64 %upper, %smallaw
  %quotient = udiv i64 %combined, %divisor
  %remainder = urem i64 %combined, %divisor
  %quotientword = trunc i64 %quotient to i32
  store i32 %quotientword, ptr %smallqp
  %smallmore = icmp ne i32 %smallindex, 0
  br i1 %smallmore, label %smallloop, label %smallend
smallend:
  %remainderword = trunc i64 %remainder to i32
  store i32 %remainderword, ptr %r
  br label %signs
largesetup:
  call void @cloth.integer.copy(ptr %r, ptr %aa, i32 %bits)
  %shorter = icmp ult i32 %alen, %bmaglen
  br i1 %shorter, label %signs, label %align
align:
  %aligned = alloca i32, i32 %n
  %gap = sub i32 %alen, %bmaglen
  call void @cloth.integer.shl(ptr %aligned, ptr %bb, i32 %bits, i32 %gap)
  br label %loop
loop:
  %bitindex = phi i32 [ %gap, %align ], [ %nextbit, %advance ]
  %comparison = call i32 @cloth.integer.compare(ptr %r, ptr %aligned, i32 %bits, i1 false)
  %large = icmp sge i32 %comparison, 0
  br i1 %large, label %take, label %advance
take:
  call void @cloth.integer.addsub(ptr %r, ptr %r, ptr %aligned, i32 %bits, i1 true)
  %word = lshr i32 %bitindex, 5
  %offset = and i32 %bitindex, 31
  %qp = getelementptr i32, ptr %q, i32 %word
  %qv = load i32, ptr %qp
  %mask = shl i32 1, %offset
  %set = or i32 %qv, %mask
  store i32 %set, ptr %qp
  br label %advance
advance:
  call void @cloth.integer.shrone(ptr %aligned, i32 %bits)
  %nextbit = sub i32 %bitindex, 1
  %more = icmp ne i32 %bitindex, 0
  br i1 %more, label %loop, label %signs
signs:
  %qnegative = xor i1 %aneg, %bneg
  br i1 %qnegative, label %negateq, label %checkr
negateq:
  call void @cloth.integer.neg(ptr %q, ptr %q, i32 %bits)
  br label %checkr
checkr:
  br i1 %aneg, label %negater, label %done
negater:
  call void @cloth.integer.neg(ptr %r, ptr %r, i32 %bits)
  br label %done
done:
  ret i32 0
}
define internal i32 @cloth.integer.power(ptr %out, ptr %a, ptr %b, i32 %bits, i1 %signed) noinline {
entry:
  %signindex = sub i32 %bits, 1
  %signbit = call i1 @cloth.integer.bit(ptr %b, i32 %signindex)
  %negative = and i1 %signed, %signbit
  br i1 %negative, label %bad, label %setup
bad:
  ret i32 3
setup:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %factor = alloca i32, i32 %n
  %temp = alloca i32, i32 %n
  call void @cloth.integer.copy(ptr %factor, ptr %a, i32 %bits)
  call void @cloth.integer.zero(ptr %out, i32 %bits)
  store i32 1, ptr %out
  %length = call i32 @cloth.integer.length(ptr %b, i32 %bits)
  %empty = icmp eq i32 %length, 0
  br i1 %empty, label %done, label %loop
loop:
  %index = phi i32 [ 0, %setup ], [ %next, %again ]
  %bit = call i1 @cloth.integer.bit(ptr %b, i32 %index)
  br i1 %bit, label %multiply, label %advance
multiply:
  call void @cloth.integer.multiply(ptr %temp, ptr %out, ptr %factor, i32 %bits)
  call void @cloth.integer.copy(ptr %out, ptr %temp, i32 %bits)
  br label %advance
advance:
  %next = add i32 %index, 1
  %more = icmp ult i32 %next, %length
  br i1 %more, label %square, label %done
square:
  call void @cloth.integer.multiply(ptr %temp, ptr %factor, ptr %factor, i32 %bits)
  call void @cloth.integer.copy(ptr %factor, ptr %temp, i32 %bits)
  %factorlen = call i32 @cloth.integer.length(ptr %factor, i32 %bits)
  %zero = icmp eq i32 %factorlen, 0
  br i1 %zero, label %zeroresult, label %unitcheck
unitcheck:
  %one = icmp eq i32 %factorlen, 1
  br i1 %one, label %done, label %again
again:
  br label %loop
zeroresult:
  call void @cloth.integer.zero(ptr %out, i32 %bits)
  br label %done
done:
  ret i32 0
}
define internal i1 @cloth.integer.checked(ptr %out, ptr %a, ptr %b, i32 %bits, i1 %signed, i32 %operation) noinline {
entry:
  %widebits = mul i32 %bits, 2
  %n = call i32 @cloth.integer.words(i32 %widebits)
  %aa = alloca i32, i32 %n
  %bb = alloca i32, i32 %n
  %result = alloca i32, i32 %n
  %roundtrip = alloca i32, i32 %n
  call void @cloth.integer.cast(ptr %aa, ptr %a, i32 %bits, i32 %widebits, i1 %signed)
  call void @cloth.integer.cast(ptr %bb, ptr %b, i32 %bits, i32 %widebits, i1 %signed)
  %mul = icmp eq i32 %operation, 2
  br i1 %mul, label %multiply, label %addsub
multiply:
  call void @cloth.integer.multiply(ptr %result, ptr %aa, ptr %bb, i32 %widebits)
  br label %finish
addsub:
  %sub = icmp eq i32 %operation, 1
  call void @cloth.integer.addsub(ptr %result, ptr %aa, ptr %bb, i32 %widebits, i1 %sub)
  br label %finish
finish:
  call void @cloth.integer.cast(ptr %out, ptr %result, i32 %widebits, i32 %bits, i1 false)
  call void @cloth.integer.cast(ptr %roundtrip, ptr %out, i32 %bits, i32 %widebits, i1 %signed)
  %comparison = call i32 @cloth.integer.compare(ptr %result, ptr %roundtrip, i32 %widebits, i1 false)
  %overflow = icmp ne i32 %comparison, 0
  ret i1 %overflow
}
""".trimIndent()
}
