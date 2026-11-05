package cloth.emitter

import cloth.data.*
import cloth.diagnostics.InternalErrors
import cloth.diagnostics.ToolchainErrors
import cloth.target.TargetLayout
import cloth.token.Keywords
import cloth.util.ExecutableFormat
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.llvm.global.LLVM.*

/** Explicit C ABI profiles selected from LLVM's normalized target triple. */
internal class NativeCABI private constructor(val targetTriple: String, val profile: Profile, val architecture: Architecture, val environment: String) {

	enum class Architecture { X86,
		X86_64,
		AARCH64
	}

	enum class Profile(val intBits: Int, val longBits: Int, val longLongBits: Int, val pointerBits: Int, val doubleFormat: ValueType.FloatingFormat, val executableFormat: ExecutableFormat) { WINDOWS_ILP32(32, 32, 64, 32, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.PE),
		WINDOWS_LLP64(32, 32, 64, 64, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.PE),
		LINUX_LP64(32, 64, 64, 64, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.ELF),
		DARWIN_LP64(32, 64, 64, 64, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.MACH_O)
	}

	val layout = TargetLayout(targetTriple, profile.pointerBits, cIntBits = profile.intBits, cLongBits = profile.longBits, cLongLongBits = profile.longLongBits, cDoubleFormat = profile.doubleFormat)
	val intType = CInt(layout.cIntBits)
	val longType = CInt(layout.cLongBits)
	val longLongType = CInt(layout.cLongLongBits)
	val doubleType = CFloat(layout.cDoubleFormat)
	val sizeType = CInt(layout.sizeBits, unsigned = true)
	val pointerBits: Int get() = layout.pointerBits
	val executableFormat: ExecutableFormat get() = profile.executableFormat
	val windows: Boolean get() = executableFormat == ExecutableFormat.PE
	val windowsAPICallConvention: Int get() = if (windows && architecture == Architecture.X86) LLVMX86StdcallCallConv else LLVMCCallConv

	/** SSE2 keeps float32/float64 operations at their declared precision on Windows x86. */
	val targetFeatures: String get() = if (architecture == Architecture.X86) "+sse2" else ""

	fun printArgument(type: ValueType): ValueType = when (type) {
		is CStruct      -> throw InternalErrors.invariant("Structs have no native print argument")
		is CClass       -> throw InternalErrors.invariant("Classes have no native print argument")
		CTypeInfo       -> throw InternalErrors.invariant("Type metadata requires normalized text output")
		ValueType.Never -> throw InternalErrors.invariant("A non-continuing expression has no printable value")
		is CArray       -> throw InternalErrors.invariant("Arrays require recursive text adaptation")
		is CNullable    -> throw InternalErrors.invariant("Nullable printing requires presence-aware text adaptation")
		ValueType.Null  -> throw InternalErrors.invariant("Bare ${Keywords.NULL} formatting has no C vararg payload")
		ValueType.Void  -> throw InternalErrors.invariant("A ${Keywords.VOID} statement has no printable value")
		is CFloat       -> doubleType
		is CInteger     -> {
			require(type.bits <= longLongType.bits) { "Native printing does not support ${type.spelling} on '$targetTriple'" }
			CInt(if (type.bits <= intType.bits) intType.bits else longLongType.bits, type.unsigned)
		}

		CBool           -> type
		CChar           -> throw InternalErrors.invariant("Characters require UTF-8 byte output instead of a native printf argument")
		CString         -> throw InternalErrors.invariant("Strings require length-aware UTF-8 byte output instead of a native printf argument")
	}

	/** The C conversion specifier matching the argument representation selected above. */
	fun printSpecifier(type: ValueType): String = when (val argument = printArgument(type)) {
		is CStruct      -> throw InternalErrors.invariant("Structs have no native print format")
		is CClass       -> throw InternalErrors.invariant("Classes have no native print format")
		CTypeInfo       -> throw InternalErrors.invariant("Type metadata requires normalized text output")
		ValueType.Never -> throw InternalErrors.invariant("A non-continuing expression has no print format")
		is CArray       -> throw InternalErrors.invariant("Arrays require recursive text adaptation")
		is CNullable    -> throw InternalErrors.invariant("Nullable printing requires presence-aware text adaptation")
		ValueType.Null  -> throw InternalErrors.invariant("Bare ${Keywords.NULL} formatting has no C vararg payload")
		ValueType.Void  -> throw InternalErrors.invariant("A ${Keywords.VOID} statement has no print format")
		is CFloat       -> "g"
		is CInteger     -> when {
			argument.unsigned && argument.bits > intType.bits -> "llu"
			argument.unsigned                                 -> "u"
			argument.bits > intType.bits                      -> "lld"
			else                                              -> "d"
		}

		CBool           -> "s"
		CChar           -> throw InternalErrors.invariant("Characters require UTF-8 byte output instead of a native printf specifier")
		CString         -> throw InternalErrors.invariant("Strings require length-aware UTF-8 byte output instead of a native printf specifier")
	}

	companion object {

		fun native(): NativeCABI {
			val triple = LLVMGetDefaultTargetTriple()
			return try {
				forTarget(triple.string)
			} finally {
				LLVMDisposeMessage(triple)
			}
		}

		fun forTarget(triple: String): NativeCABI {
			val normalized = BytePointer(triple).use { LLVMNormalizeTargetTriple(it) }
			val target = try {
				normalized.string
			} finally {
				LLVMDisposeMessage(normalized)
			}

			fun unsupported(): Nothing = throw ToolchainErrors.target(triple, target)
			val parts = target.split('-')
			if (parts.size !in 3..4 || parts.any { it.isEmpty() }) unsupported()
			val architecture = when (parts[0]) {
				"i686"             -> Architecture.X86
				"x86_64"           -> Architecture.X86_64
				"aarch64", "arm64" -> Architecture.AARCH64
				else               -> unsupported()
			}
			val os = parts[2]
			val environment = parts.getOrElse(3) { "" }
			val profile = when {
				os.matches(Regex("(?:windows|win32)(?:[0-9]+(?:\\.[0-9]+)*)?")) && environment.matches(Regex("msvc(?:[0-9]+(?:\\.[0-9]+)*)?"))             -> if (architecture == Architecture.X86) Profile.WINDOWS_ILP32 else Profile.WINDOWS_LLP64
				architecture != Architecture.X86 && os == "linux" && environment in setOf("gnu", "musl")                                                   -> Profile.LINUX_LP64
				architecture != Architecture.X86 && os.matches(Regex("(?:darwin|macosx)(?:[0-9]+(?:\\.[0-9]+)*)?")) && environment in setOf("", "unknown") -> Profile.DARWIN_LP64
				else                                                                                                                                       -> unsupported()
			}
			return NativeCABI(target, profile, architecture, environment)
		}
	}
}
