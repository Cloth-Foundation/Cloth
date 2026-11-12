package cloth

import cloth.emitter.LLVMEmitter
import cloth.emitter.NativeCABI
import cloth.semantic.ProgramAnalyzer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericStateEmissionTest {

	private val completion get() = processCompletion().replace("\r\n", "\n")

	@Test
	fun `generic state calls finish defaults before selecting and pinning the payload`(@TempDir directory: Path) {
		val source = """
			fn<int> note(int marker, int value) { print(marker); return value }
			fn<Actor> capture(Actor actor) { print(1); return actor }
			fn<int> shift(Actor actor, int value) {
			    print(3)
			    actor -> Working("working")
			    return value
			}
			typedef<class> Actor (def<int> seed) {
			    pub state<T, string> ::produce<T: Number = int<8>>(Actor target, opt<T> value ?: shift(target, self.seed) -> T, opt<int> marker ?: note(4, 0))
			    default: ("ready") pub state<string> Ready {
			        mt<U, string> produce<U>(Actor other, U item, int code) { print(5); return item, state + "!" }
			        pub mt<V> status<V: Number>(V value) = value + value
			    }
			    pub state<string> Working {
			        mt<U, string> produce<U>(Actor other, U item, int code) {
			            print(6)
			            self -> Ready("after")
			            return item + item, state + "!"
			        }
			    }
			    ~Actor { println(self::status<int>(2)) }
			}
			fn<int> main() {
			    def item = Actor::new(5)
			    def<int<8>, string> first, text = capture(item)::produce(item, marker=note(2, 7))
			    if (first != 10 || text != "working!" || !(item is Ready)) return 1
			    def<float, string> next, after = item::produce<float>(item, value=1.5f, marker=0)
			    if (next != 1.5f || after != "after!") return 2
			    println("")
			    item::delete
			    return 0
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("generic_state_order_${abi.layout.sizeBits}"))
			assertEquals("12365\n4\n" + completion, output)
		}
		val unit = structUnit(source)
		for ((index, triple) in structTargetTriples.withIndex()) {
			val emitter = LLVMEmitter(NativeCABI.forTarget(triple))
			assertTrue(Files.size(emitter.compileObject(unit, directory.resolve("generic_state_$index.o"))) > 0, triple)
		}
	}

	@Test
	fun `recursive generic state calls gain targets from classes discovered later`(@TempDir directory: Path) {
		val source = """
			typedef<class> Base {
			    pub state<T> ::round<T: Number>(T value, int depth)
			    default: state<void> Ready {
			        mt<U> round<U>(U value, int depth) {
			            if (depth == 0) return value
			            return self::round<U>(value + value, depth - 1)
			        }
			    }
			}
			typedef<class> Boost<E> : Base {
			    default: state<void> Fast { mt<V> round<V>(V item, int depth) = item + item + item }
			}
			fn<Base> actual<E>(E label) = Boost<E>::new
			fn<Base> later<E>(E label) = actual(label)
			fn main() {
			    def base = Base::new
			    println(base::round(3, 2))
			    def boosted = later("late")
			    println(boosted::round(3, 2))
			    base::delete
			    boosted::delete
			}
		"""
		for (abi in structRunners()) {
			val output = runStructProgram(abi, source, directory.resolve("generic_state_late_${abi.layout.sizeBits}"))
			assertEquals("12\n9\n" + completion, output)
		}
	}

	@Test
	fun `an imported generic state keeps each attaching host bound and defining scopes`(@TempDir directory: Path) {
		val files = genericSourceFiles(directory, mapOf(
			"StateLib.co" to """
				pub typedef<interface> Host { mt<string> title() }
				pub typedef<interface> Left { mt<string> label() }
				pub typedef<interface> Right { mt<string> label() }
				fn<string> ending() = "!"
				typedef<state<string>> pub Ready for Host {
				    mt<T, string> build<U, T>(U value) = T::new(), state + self.title() + value.label() + ending()
				}
			""",
			"Hosts.co" to """
				using StateLib: Host, Left, Right, Ready
				fn<int> seed(int value) = value + 2
				pub typedef<class> First : Host {
				    override: mt<string> title() = "1"
				    pub state<T, string> ::build<U: Left, T: new(opt<int> id ?: seed(5))>(U input)
				    default: ("first:") state Ready
				}
				pub typedef<class> Second : Host {
				    override: mt<string> title() = "2"
				    pub state<T, string> ::build<U: Right, T: new(opt<int> id ?: seed(6))>(U input)
				    default: ("second:") state Ready
				}
			""",
			"Main.co" to """
				using StateLib: Left, Right
				using Hosts: First, Second
				fn<int> seed(int value) = 0
				fn<string> ending() = "wrong"
				typedef<class> Local (pub def<int> id)
				typedef<class> LabeledLeft : Left { override: mt<string> label() = "L" }
				typedef<class> LabeledRight : Right { override: mt<string> label() = "R" }
				fn main() {
				    def first = First::new
				    def second = Second::new
				    def left = LabeledLeft::new
				    def right = LabeledRight::new
				    def<Local, string> one, firstText = first::build<_, Local>(left)
				    def<Local, string> two, secondText = second::build<_, Local>(right)
				    println(one.id)
				    println(firstText)
				    println(two.id)
				    println(secondText)
				    one::delete
				    two::delete
				    left::delete
				    right::delete
				    first::delete
				    second::delete
				}
			""",
		))
		for (abi in structRunners()) {
			val program = ProgramAnalyzer(abi.layout).analyze(files.fork())
			val executable = LLVMEmitter(abi).compile(program, directory.resolve("generic_attached_state_${abi.layout.sizeBits}"))
			assertEquals("7\nfirst:1L!\n8\nsecond:2R!\n" + completion, runExecutable(executable).replace("\r\n", "\n"))
		}
	}

	@Test
	fun `generic state dispatch checks for a body and a live receiver after effects`(@TempDir directory: Path) {
		val declarations = """
			typedef<class> Actor {
			    pub state ::act<T: Number>(Actor item, opt<T> value ?: move(item) -> T)
			    default: state<void> Ready { mt act<U>(Actor other, U item) { exit(19) } }
			    pub state<void> Dead {}
			}
			fn<int> move(Actor item) { item -> Dead; println("moved"); return 1 }
			fn<int> erase(Actor item) { item::delete; println("deleted"); return 1 }
		"""
		val cases = listOf(
			"item::act<int>(item)" to "moved\nInvalid state for 'act(Actor, int)'\n",
			"item::act<int>(item, erase(item))" to "deleted\nClass object is no longer live\n",
		)
		for (abi in structRunners()) for ((index, case) in cases.withIndex()) {
			val (call, expected) = case
			val source = "$declarations\nfn main() { def item = Actor::new; $call }"
			val output = runStructProgram(abi, source, directory.resolve("generic_state_error_${abi.layout.sizeBits}_$index"), status = 1)
			assertEquals(expected, output)
		}
	}
}
