package cloth

import cloth.diagnostics.DiagnosticCode
import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.SemanticException
import cloth.semantic.typing.FunctionAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ClassInheritanceTest {

	private val inheritance = """
		fn<int> trace(int n) { print(n); return n }
		typedef<class> Base (pub def<string> title, opt<int> n ?: trace(2)) {
		    def<int> baseValue = trace(3) + n
		    Base(string title, float marker) : (title) {
		        if (self is Child) print(9)
		    }
		    pub mt<int> read() = baseValue
		    ~Base { print(title); println(self.read()) }
		}
		typedef<class> Middle (string title) : Base(title, 0f) {
		    def<int> middleValue = trace(4)
		    ~Middle { print(middleValue) }
		}
		final typedef<class> Child (string title, pub def<int> value ?: trace(1)) : Middle(title) {
		    def<string> label = "child"
		    pub mt<int> sum() = self.read() + self.value
		    ~Child { print(self.label); print(self.sum()) }
		}
		typedef<class> Other
		fn<Object> widen(Base value) = value
		fn<int> readChild(Object? value) {
		    if (value is Child && value is Base) return value.sum()
		    return 0
		}
		fn<int> main() {
		    def child = Child::new("base")
		    def<Object> alias = widen(child)
		    def<Object?> maybe = child
		    if (alias::type != Object::type) return 1
		    if (alias is Other || !(alias is Base)) return 2
		    if (readChild(maybe) != 6 || readChild(null) != 0) return 3
		    alias::delete
		    return 0
		}
	"""

	@Test
	fun `upcast retains concrete identity layout and destructor chain`(@TempDir directory: Path) {
		for (abi in structRunners()) {
			val output = runStructProgram(abi, inheritance, directory.resolve("inheritance_${abi.layout.sizeBits}"))
			assertEquals("1234child64base5\n" + processCompletion().replace("\r\n", "\n"), output)
		}
	}

	@Test
	fun `inheritance verifies and emits objects for every supported profile`(@TempDir directory: Path) {
		val unit = structUnit(inheritance)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertContains(emitter.emitExecutable(unit), "concrete_class")
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("inheritance_$index.o"))) > 0)
		}
	}

	@Test
	fun `class tests reject a stale upcast after slot reuse`(@TempDir directory: Path) {
		val source = """
			typedef<class> Base
			typedef<class> Child : Base()
			fn<Object> escape(Child value) = value
			fn main() {
			    def child = Child::new
			    def stale = escape(child)
			    child::delete
			    def other = Base::new
			    println(stale is Child)
			}
		"""
		for (abi in structRunners()) {
			assertEquals("Class object is no longer live\n", runStructProgram(abi, source, directory.resolve("stale_${abi.layout.sizeBits}"), 1))
		}
	}

	@Test
	fun `base construction cannot start the concrete destructor chain`(@TempDir directory: Path) {
		val source = """
			typedef<class> Base {
			    Base(bool abort) : () { if (abort) self::delete }
			}
			typedef<class> Child : Base(true) {
			    ~Child { println("child destructor") }
			}
			fn main() { def child = Child::new }
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("partial_${abi.layout.sizeBits}"), 1)
			assertEquals("Class object is still under construction\n", output)
		}
	}

	@Test
	fun `invalid bases and stale local upcasts report source errors`() {
		for (source in listOf(
			"typedef<class> Child : Missing()",
			"final typedef<class> Base\ntypedef<class> Child : Base()",
			"typedef<class> Base : Child()\ntypedef<class> Child : Base()",
			"typedef<class> Object",
		)) {
			val error = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(source)) }
			assertEquals(DiagnosticCode.INVALID_CLASS_MEMBER, error.diagnosticCode)
		}
		val error = assertFailsWith<SemanticException> {
			FunctionAnalyzer(nativeTestLayout).analyze(structUnit("""
				typedef<class> Child
				fn main() {
				    def child = Child::new
				    def<Object> alias = child
				    child::delete
				    println(alias is Child)
				}
			"""))
		}
		assertEquals(DiagnosticCode.DELETED_CLASS_ACCESS, error.diagnosticCode)
	}

	@Test
	fun `narrowing follows writes and recognizes effects of the selected class method`() {
		val reassigned = """
			typedef<class> Other
			typedef<class> Child { pub mt<int> read() = 1 }
			fn main() {
			    def<Object> value = Child::new
			    if (value is Child) {
			        value = Other::new
			        println(value.read())
			    }
			}
		"""
		val first = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(reassigned)) }
		assertEquals(DiagnosticCode.UNKNOWN_MEMBER, first.diagnosticCode)

		for ((declared, checked) in listOf("Object" to "Child", "Child" to "Object")) {
			val mutated = """
				typedef<class> Item (pub def<int> number)
				typedef<class> Holder (pub def<Item?> item)
				typedef<class> Child {
				    pub mt clear(Holder holder) { holder.item = null }
				}
				fn check($declared value, Holder holder) {
				    if (holder.item != null && value is $checked) {
				        value.clear(holder)
				        println(holder.item.number)
				    }
				}
				fn main() {}
			"""
			val second = assertFailsWith<SemanticException> { FunctionAnalyzer(nativeTestLayout).analyze(structUnit(mutated)) }
			assertEquals(DiagnosticCode.NULLABLE_PAYLOAD_ACCESS, second.diagnosticCode)
		}
	}
}
