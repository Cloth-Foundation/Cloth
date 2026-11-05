package cloth.emitter.types.kernel

/** Bounded i32-word helpers. Radix conversion divides by radix powers up to 1e9,
 * keeping all intermediates within i64 and avoiding one word scan per digit.
 */
internal object IntegerMemberKernelIR {

	val source = """
declare i32 @llvm.cttz.i32(i32, i1 immarg)

define internal i32 @cloth.integer.trailing(ptr %input, i32 %bits) noinline {
entry:
  %n = call i32 @cloth.integer.words(i32 %bits)
  br label %scan
scan:
  %i = phi i32 [ 0, %entry ], [ %next, %again ]
  %address = getelementptr i32, ptr %input, i32 %i
  %value = load i32, ptr %address
  %nonzero = icmp ne i32 %value, 0
  br i1 %nonzero, label %found, label %again
again:
  %next = add i32 %i, 1
  %more = icmp ult i32 %next, %n
  br i1 %more, label %scan, label %zero
found:
  %zeros = call i32 @llvm.cttz.i32(i32 %value, i1 false)
  %base = mul i32 %i, 32
  %count = add i32 %base, %zeros
  ret i32 %count
zero:
  ret i32 %bits
}

define internal i32 @cloth.integer.radix(ptr %out, ptr %input, i32 %bits, i1 %signed, i32 %radix, i32 %capacity) noinline {
entry:
  %room = icmp ugt i32 %capacity, 1
  %minbase = icmp uge i32 %radix, 2
  %maxbase = icmp ule i32 %radix, 36
  %basevalid = and i1 %minbase, %maxbase
  %valid = and i1 %room, %basevalid
  br i1 %valid, label %setup, label %bad
setup:
  %limitpower = udiv i32 1000000000, %radix
  br label %power
power:
  %divisor = phi i32 [ %radix, %setup ], [ %nextdivisor, %grow ]
  %digitsperchunk = phi i32 [ 1, %setup ], [ %nextdigits, %grow ]
  %cangrow = icmp ule i32 %divisor, %limitpower
  br i1 %cangrow, label %grow, label %magnitude
grow:
  %nextdivisor = mul i32 %divisor, %radix
  %nextdigits = add i32 %digitsperchunk, 1
  br label %power
magnitude:
  %widebase = zext i32 %divisor to i64
  %n = call i32 @cloth.integer.words(i32 %bits)
  %scratch = alloca i32, i32 %n
  %signindex = sub i32 %bits, 1
  %signbit = call i1 @cloth.integer.bit(ptr %input, i32 %signindex)
  %negative = and i1 %signed, %signbit
  %limit = sub i32 %capacity, 1
  br i1 %negative, label %negate, label %positive
negate:
  call void @cloth.integer.neg(ptr %scratch, ptr %input, i32 %bits)
  br label %chunks
positive:
  call void @cloth.integer.copy(ptr %scratch, ptr %input, i32 %bits)
  br label %chunks
chunks:
  %used = phi i32 [ %n, %negate ], [ %n, %positive ], [ %active, %chunkend ]
  %position = phi i32 [ 0, %negate ], [ 0, %positive ], [ %nextposition, %chunkend ]
  br label %trim
trim:
  %remaining = phi i32 [ %used, %chunks ], [ %topindex, %trimagain ]
  %topindex = sub i32 %remaining, 1
  %top = getelementptr i32, ptr %scratch, i32 %topindex
  %topvalue = load i32, ptr %top
  %topzero = icmp eq i32 %topvalue, 0
  %multiple = icmp ugt i32 %remaining, 1
  %skip = and i1 %topzero, %multiple
  br i1 %skip, label %trimagain, label %divide
trimagain:
  br label %trim
divide:
  %active = phi i32 [ %remaining, %trim ]
  br label %word
word:
  %left = phi i32 [ %active, %divide ], [ %index, %word ]
  %carry = phi i64 [ 0, %divide ], [ %remainder, %word ]
  %any = phi i32 [ 0, %divide ], [ %combinedquotient, %word ]
  %index = sub i32 %left, 1
  %address = getelementptr i32, ptr %scratch, i32 %index
  %value = load i32, ptr %address
  %wide = zext i32 %value to i64
  %high = shl i64 %carry, 32
  %dividend = or i64 %high, %wide
  %quotient = udiv i64 %dividend, %widebase
  %remainder = urem i64 %dividend, %widebase
  %low = trunc i64 %quotient to i32
  store i32 %low, ptr %address
  %combinedquotient = or i32 %any, %low
  %morewords = icmp ne i32 %index, 0
  br i1 %morewords, label %word, label %digits
digits:
  %morechunks = icmp ne i32 %combinedquotient, 0
  %minimumdigits = select i1 %morechunks, i32 %digitsperchunk, i32 1
  %chunk = trunc i64 %remainder to i32
  br label %digit
digit:
  %part = phi i32 [ %chunk, %digits ], [ %nextpart, %putdigit ]
  %count = phi i32 [ 0, %digits ], [ %nextcount, %putdigit ]
  %offset = phi i32 [ %position, %digits ], [ %nextposition, %putdigit ]
  %fits = icmp ult i32 %offset, %limit
  br i1 %fits, label %putdigit, label %bad
putdigit:
  %digitvalue = urem i32 %part, %radix
  %numeric = icmp ult i32 %digitvalue, 10
  %bias = select i1 %numeric, i32 48, i32 87
  %ascii = add i32 %digitvalue, %bias
  %byte = trunc i32 %ascii to i8
  %output = getelementptr i8, ptr %out, i32 %offset
  store i8 %byte, ptr %output
  %nextpart = udiv i32 %part, %radix
  %nextposition = add i32 %offset, 1
  %nextcount = add i32 %count, 1
  %padding = icmp ult i32 %nextcount, %minimumdigits
  %nonzero = icmp ne i32 %nextpart, 0
  %moredigits = or i1 %padding, %nonzero
  br i1 %moredigits, label %digit, label %chunkend
chunkend:
  br i1 %morechunks, label %chunks, label %sign
sign:
  br i1 %negative, label %signcheck, label %reverse
signcheck:
  %signfits = icmp ult i32 %nextposition, %limit
  br i1 %signfits, label %putsign, label %bad
putsign:
  %signaddress = getelementptr i8, ptr %out, i32 %nextposition
  store i8 45, ptr %signaddress
  %signedlength = add i32 %nextposition, 1
  br label %reverse
reverse:
  %length = phi i32 [ %nextposition, %sign ], [ %signedlength, %putsign ]
  %last = sub i32 %length, 1
  %half = lshr i32 %length, 1
  br label %swapcheck
swapcheck:
  %i = phi i32 [ 0, %reverse ], [ %next, %swap ]
  %more = icmp ult i32 %i, %half
  br i1 %more, label %swap, label %done
swap:
  %opposite = sub i32 %last, %i
  %front = getelementptr i8, ptr %out, i32 %i
  %back = getelementptr i8, ptr %out, i32 %opposite
  %frontbyte = load i8, ptr %front
  %backbyte = load i8, ptr %back
  store i8 %backbyte, ptr %front
  store i8 %frontbyte, ptr %back
  %next = add i32 %i, 1
  br label %swapcheck
done:
  %terminator = getelementptr i8, ptr %out, i32 %length
  store i8 0, ptr %terminator
  ret i32 %length
bad:
  ret i32 -1
}
""".trimIndent()
}
