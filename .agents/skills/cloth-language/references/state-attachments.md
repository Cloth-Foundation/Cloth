# State attachments

State Runtime Stage 5 supports standalone state definitions and explicit class
attachments. A declaration has the form `typedef<state<Payload>> pub Name for Host`
with a state body. Omit `pub` to keep its name local to the defining file.
[Shared semantics](semantics.md#top-level-states) ·
[State plan](../../../../todo/open-questions-plan.md#state-0-implementation-stages)

```cloth
typedef<interface> Session { mt<string> title() }
typedef<struct> SessionInfo { string name }

typedef<state<SessionInfo>> pub Dead for Session {
    init { println(self.title()) }
    pub mt<string> label() = state.name
}

typedef<class> Player (def<string> name) : Session {
    override: mt<string> title() = self.name
    default: ({ self.name }) state Dead
}
```

When the state lives in another file, `using File : Dead` binds its exported
name. Use `state Dead` for an additional state and `default: state Dead` for a
void default. A class adding states selects exactly one local default from
its nested and attached states. A subclass that adds none inherits its
effective default.

## Scope and checks

- Selected and wildcard imports retain the original definition. State names
  have their own attachment namespace. An attachment uses the public or
  private visibility of its definition. A public attachment is available to
  external callers through the receiver's supported states, including in files
  that import just the receiver type.
- The contract is a class or an interface. The attaching class must be the
  named class, a subclass, or a nominal conformer to the interface. Closed
  type arguments participate in this check. State-name conflicts within a
  class or with inherited states produce C3126. An unresolved attachment or
  incompatible host produces C3131.
- The entry hook and methods use `self` typed as the declared host contract,
  ordinary public member access, and the state declaration's file scope.
  Direct `self` state tests, transitions and state calls use the attaching
  class's supported states and contracts. Each attaching class must provide
  accessible transition targets with compatible payloads. The initial
  payload expression instead uses the selecting class's constructed `self`
  and its file and member scopes.

## Compiler routing

[ClassParser](../../../../Compiler/src/main/kotlin/cloth/parser/subs/ClassParser.kt)
parses the shared entry and method body forms.
[FileBindings](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/FileBindings.kt)
registers standalone definitions before binding imports.
[StateCatalog](../../../../Compiler/src/main/kotlin/cloth/semantic/declarations/StateCatalog.kt)
checks host eligibility and adds each attachment to the class catalog.
[GenericBodyViews](../../../../Compiler/src/main/kotlin/cloth/semantic/generics/GenericBodyViews.kt)
keeps one body view per attaching class, including closed generic hosts.
Each view preserves the original source tokens and has independent typing
and state target facts. Executable bodies are checked per attachment.
[BodyTyping](../../../../Compiler/src/main/kotlin/cloth/semantic/typing/BodyTyping.kt)
uses the attachment scope for direct receiver state operations.

Default payload evaluation and the entry hook have separate callable scopes.
The constructor evaluates the payload after field construction, installs the
activation and invokes the hook through the existing state runtime.
[EmitFunctions](../../../../Compiler/src/main/kotlin/cloth/emitter/callables/EmitFunctions.kt) ·
[Activation and dispatch](state-runtime.md)

The maintained Shuttle project exercises attached defaults, reuse across
unrelated hosts and an interface fallback in the entry hook.
[Project and release verification](state-publication.md)

## Stage 5 verification

`StateAttachmentTest` covers parser forms, selected and wildcard imports,
nominal class and interface host checks, state visibility, conflicts and
missing transition targets. It checks that an inherited default retains its
attachment identity, two unrelated hosts resolve the same transition body
against distinct target payload types, and each file resolves its own helpers.
[Attachment fixtures](../../../../Compiler/src/test/kotlin/cloth/StateAttachmentTest.kt)

The full offline Compiler check ran 2,070 tests in 197 suites and found one
parser assertion for the former unsupported syntax. After updating that
assertion, the focused state and parser run passed all 51 tests in six suites.
The dedicated import and generic tasks passed 203 test runs.

The imported fixture ran through the public Compiler request on Windows x64
and x86. Its executable IR verified and its objects emitted across all nine
target profiles. The documented example ran through the Windows x64 Compiler
CLI.
