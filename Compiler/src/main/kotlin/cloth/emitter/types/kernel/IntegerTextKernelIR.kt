package cloth.emitter.types.kernel

/** Decimal conversion of canonical fixed-width bytes. Never mutates the input.
 * i32 chunks and i64 intermediates support every target without huge scalar libcalls.
 */
internal object IntegerTextKernelIR {

	val source = """
define internal i32 @cloth.integer.decimal(ptr %out, ptr %input, i32 %bits, i1 %signed, i32 %capacity) noinline {
entry:
  %room = icmp ugt i32 %capacity, 1
  br i1 %room, label %setup, label %bad
setup:
  %n = call i32 @cloth.integer.words(i32 %bits)
  %magnitude = alloca i32, i32 %n
  %signindex = sub i32 %bits, 1
  %signbit = call i1 @cloth.integer.bit(ptr %input, i32 %signindex)
  %negative = and i1 %signed, %signbit
  %limit = sub i32 %capacity, 1
  br i1 %negative, label %negate, label %positive
negate:
  call void @cloth.integer.neg(ptr %magnitude, ptr %input, i32 %bits)
  br label %chunks
positive:
  call void @cloth.integer.copy(ptr %magnitude, ptr %input, i32 %bits)
  br label %chunks
chunks:
  %used = phi i32 [ %n, %negate ], [ %n, %positive ], [ %active, %chunkend ]
  %position = phi i32 [ 0, %negate ], [ 0, %positive ], [ %nextposition, %chunkend ]
  br label %trim
trim:
  %remaining = phi i32 [ %used, %chunks ], [ %topindex, %trimagain ]
  %topindex = sub i32 %remaining, 1
  %top = getelementptr i32, ptr %magnitude, i32 %topindex
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
  %address = getelementptr i32, ptr %magnitude, i32 %index
  %value = load i32, ptr %address
  %wide = zext i32 %value to i64
  %high = shl i64 %carry, 32
  %dividend = or i64 %high, %wide
  %quotient = udiv i64 %dividend, 1000000000
  %remainder = urem i64 %dividend, 1000000000
  %low = trunc i64 %quotient to i32
  store i32 %low, ptr %address
  %combinedquotient = or i32 %any, %low
  %morewords = icmp ne i32 %index, 0
  br i1 %morewords, label %word, label %digits
digits:
  %morechunks = icmp ne i32 %combinedquotient, 0
  %minimumdigits = select i1 %morechunks, i32 9, i32 1
  %chunk = trunc i64 %remainder to i32
  br label %digit
digit:
  %part = phi i32 [ %chunk, %digits ], [ %nextpart, %putdigit ]
  %count = phi i32 [ 0, %digits ], [ %nextcount, %putdigit ]
  %offset = phi i32 [ %position, %digits ], [ %nextposition, %putdigit ]
  %fits = icmp ult i32 %offset, %limit
  br i1 %fits, label %putdigit, label %bad
putdigit:
  %decimal = urem i32 %part, 10
  %ascii = add i32 %decimal, 48
  %byte = trunc i32 %ascii to i8
  %output = getelementptr i8, ptr %out, i32 %offset
  store i8 %byte, ptr %output
  %nextpart = udiv i32 %part, 10
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
