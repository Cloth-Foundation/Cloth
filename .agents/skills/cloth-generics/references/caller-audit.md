# Generic/List caller audit (2026-11-08)

The first generic class/struct slice and public source List are implemented.
This audit records consumers and migration boundaries; it performs no Bootstrap
migration. Read [Stage 6](stage-6.md) for verification evidence and
[follow-ups](../../../../todo/generic-follow-ups.md) for separate future designs.

## Current callers

| Source | Observed contract | Integration decision |
| --- | --- | --- |
| [List.co](../../../../Standard-Library/cloth/collections/List.co) | Private generic slots own independent value buffers; class-valued T keeps identity. | Retain the source API and checked copy/export behavior. |
| [Library.co](../../../../Standard-Library/cloth/Library.co) | Imports List for standalone declaration validation; imports are not reexports. | Keep consumers importing the actual List file explicitly. |
| [Collections/Main.co](../../../../examples/Collections/app/Main.co) | Aliases share a list; removed/exported/copied values are independent; all created lists are deleted. | Maintain as the public-library consumer example. |
| [ArrayBackedList.co](../../../../examples/ArrayBackedList.co), [ArrayBackedRows.co](../../../../examples/ArrayBackedRows.co) | Concrete private-array/struct-slot proofs with existing regression tests. | Preserve as concrete storage proofs; their older comments do not define current generic availability. |
| [TokenList.co](../../../../Bootstrap/Frontend/token/TokenList.co) | TokenList owns TokenNode objects and Tokens; TokenCursor in this same file borrows nodes/tokens. | Preserve the owning collection and borrowed cursor. Generic List is not a drop-in ownership replacement. |
| [Lexer.co](../../../../Bootstrap/Frontend/lexer/Lexer.co) | One scan per instance; success transfers the token list; failure destroys the partial list and retains one owned failure. | Preserve transfer/error cleanup and the activeTokens detach points. |
| [Token.co](../../../../Bootstrap/Frontend/token/Token.co), [LexerError.co](../../../../Bootstrap/Frontend/lexer/LexerError.co) | Tokens and failures own their TokenSpan. Spans borrow optional SourceFile identity. | Keep source lifetime separate from token/span destruction. |
| [Bootstrap/Main.co](../../../../Bootstrap/Frontend/Main.co) | Driver finishes cursor/list cleanup, then deletes Lexer and SourceFile. | Preserve this order and the string-input scope. |
| Compiler/Shuttle Kotlin implementation | Host Kotlin collections hold compiler/manifest data. | They are not Cloth source List consumers; no host-language container replacement is part of this delivery. |

The source search covers `Bootstrap`, `Standard-Library` and `examples` for List,
TokenList/TokenCursor and the concrete StringList/RowList names. The archived
inventory retains the matching paths/lines and preserved source hashes.
`examples/Test.co` retains intended constrained/interface syntax. Existing math
sources are unrelated to this migration and remain untouched.

## Bootstrap ownership evidence

TokenList's destructor traverses nodes, deletes each node's token, then deletes
the node. Token's destructor deletes its span. TokenSpan contains a SourceFile
class identity with no ownership transfer. LexerFailure also deletes its span.
TokenCursor holds a nullable TokenNode identity and returns borrowed Token values;
its comment requires cursor deletion before list deletion.

Lexer.scanTokens creates the list and records activeTokens. On failure it clears
activeTokens, deletes the partial list and returns null. On success it appends EOF,
clears activeTokens and returns the list. Deleting the lexer therefore leaves a
successfully transferred list alive. The lexer retains and deletes its failure.
lexTokens deletes its cursor before its list; the outer driver deletes SourceFile
last. BootstrapLexerTest independently exercises tokens after deleting the lexer,
first-error results and source lifetime.

Default `List<Token>` owns its slot/value buffers and copies token identities.
Clear/destruction would not delete Tokens or TokenSpans. Copying such a list would
also alias the Tokens. Replacing TokenList alone would lose destruction behavior;
adding token deletion to multiple independent wrappers could double-delete shared
identities. Generic availability does not settle an owning-container copy policy.

## Gates for a separately requested migration

1. Select an owning adapter/transfer policy for Tokens and spans, including what
   copying the owner means. Do not change the default List class-element contract.
2. Define cursor borrowing, invalidation and deletion order through list mutation,
   owner destruction and source destruction. Keep checked class identities and no
   programmer-visible pointer/reference syntax.
3. Prove successful scan transfer, partial-token teardown at the first error,
   lexer/failure destruction, repeated EOF behavior and SourceFile survival until
   the last borrower is finished.
4. Keep the Kotlin/Bootstrap token-and-first-error differential oracle, Unicode
   positions and native lifetime tests. A successful ordinary List smoke test
   cannot replace those consumer-specific checks.

No new ownership, iterator, borrowing or generic-interface rule is selected by
this audit. Bootstrap sources and intended Test.co are preserved byte-for-byte
against the Stage 6 baseline.
