package cloth.emitter

import cloth.diagnostics.InternalErrors
import cloth.diagnostics.ToolchainErrors
import cloth.semantic.ValueType
import cloth.util.ExecutableFormat
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.llvm.global.LLVM.*

/** Explicit C ABI profiles selected from LLVM's normalized target triple. */
internal class NativeCABI private constructor(val targetTriple: String, val profile: Profile, val architecture: Architecture, val environment: String) {

	enum class Architecture { X86_64, AARCH64 }
	enum class Profile(val intBits: Int, val longBits: Int, val longLongBits: Int, val pointerBits: Int, val doubleFormat: ValueType.FloatingFormat, val executableFormat: ExecutableFormat) { WINDOWS_LLP64(32, 32, 64, 64, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.PE), LINUX_LP64(32, 64, 64, 64, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.ELF), DARWIN_LP64(32, 64, 64, 64, ValueType.FloatingFormat.DOUBLE, ExecutableFormat.MACH_O)
	}

	val intType = ValueType.Integer(profile.intBits)
	val longType = ValueType.Integer(profile.longBits)
	val longLongType = ValueType.Integer(profile.longLongBits)
	val doubleType = ValueType.Floating(profile.doubleFormat)
	val sizeType = ValueType.Integer(profile.pointerBits, unsigned = true)
	val pointerBits: Int get() = profile.pointerBits
	val executableFormat: ExecutableFormat get() = profile.executableFormat

	fun printArgument(type: ValueType): ValueType = when (type) {
		ValueType.Void        -> throw InternalErrors.invariant("A void statement has no printable value")
		is ValueType.Floating -> doubleType
		is ValueType.Integer  -> {
			require(type.bits <= longLongType.bits) { "Native printing does not support ${type.spelling} on '$targetTriple'" }
			ValueType.Integer(if (type.bits <= intType.bits) intType.bits else longLongType.bits, type.unsigned)
		}

		ValueType.Bool        -> type
		ValueType.Character   -> throw InternalErrors.invariant("Characters require UTF-8 byte output instead of a native printf argument")
		ValueType.Text        -> throw InternalErrors.invariant("Strings require length-aware UTF-8 byte output instead of a native printf argument")
	}

	/** The C conversion specifier matching the argument representation selected above. */
	fun printSpecifier(type: ValueType): String = when (val argument = printArgument(type)) {
		ValueType.Void        -> throw InternalErrors.invariant("A void statement has no print format")
		is ValueType.Floating -> "g"
		is ValueType.Integer  -> when {
			argument.unsigned && argument.bits > intType.bits -> "llu"
			argument.unsigned                                 -> "u"
			argument.bits > intType.bits                      -> "lld"
			else                                              -> "d"
		}

		ValueType.Bool        -> "s"
		ValueType.Character   -> throw InternalErrors.invariant("Characters require UTF-8 byte output instead of a native printf specifier")
		ValueType.Text        -> throw InternalErrors.invariant("Strings require length-aware UTF-8 byte output instead of a native printf specifier")
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
				"x86_64"           -> Architecture.X86_64
				"aarch64", "arm64" -> Architecture.AARCH64
				else               -> unsupported()
			}
			val os = parts[2]
			val environment = parts.getOrElse(3) { "" }
			val profile = when {
				os.matches(Regex("(?:windows|win32)(?:[0-9]+(?:\\.[0-9]+)*)?")) && environment.matches(Regex("msvc(?:[0-9]+(?:\\.[0-9]+)*)?")) -> Profile.WINDOWS_LLP64
				os == "linux" && environment in setOf("gnu", "musl")                                                                           -> Profile.LINUX_LP64
				os.matches(Regex("(?:darwin|macosx)(?:[0-9]+(?:\\.[0-9]+)*)?")) && environment in setOf("", "unknown")                         -> Profile.DARWIN_LP64
				else                                                                                                                           -> unsupported()
			}
			return NativeCABI(target, profile, architecture, environment)
		}
	}
}
