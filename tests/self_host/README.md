# Self-hosted compiler tests

Bazel is authoritative for tests in this repository. `//tests:presubmit` is the
normal edit loop; `//tests:full` is the release and compatibility gate. Shuttle
remains Cloth's public build system and is exercised by one explicit integration
target against a declared, temporary repository staging area.

## Migration closure

Stage 48 retired the `BootstrapMain.co` argument dispatcher after preserving
each capability as an independently named target:

| Legacy capability | Authoritative target |
| --- | --- |
| Lexer foundation and literals | `//tests/self_host/tests:lexer_*_test` |
| Declaration substrate and grammar | `//tests/self_host/tests:declaration_*_test` |
| Definition and expression parsing | `//tests/self_host/tests:*parser*_test` |
| Package frontend coordination | `//tests/self_host/tests:frontend_coordinator*_test` |
| Diagnostic catalogs and rendering | `//tests/self_host/tests:diagnostic_renderer_test` |
| Semantic package symbols and identity | `//tests/self_host/tests:semantic_package_test` |
| Import scopes and declared type binding | `//tests/self_host/tests:semantic_resolution_test` |
| Semantic ownership and input failures | `//tests/self_host/tools/semantic_failure:failure_tests` |
| Production check streams and status | `//tests/self_host/tools/driver:tests` |
| Syntax storage and trees | `//tests/self_host/tests:syntax_*_test` |
| GC and depth modes | `//tests/self_host/tests:extended` |
| Declaration failure modes | `//tests/self_host/tools/declaration_failure:failure_tests` |
| Package frontend failures | `//tests/self_host/tools/frontend_failure:failure_tests` |
| Syntax storage failures | `//tests/self_host/tools/syntax_storage_failure:failure_tests` |
| Syntax tree failure modes | `//tests/self_host/tools/syntax_tree_failure:failure_tests` |
| Five canonical record writers | `//tests/self_host/tools/*_records` |
| C++ differential regression audit | `//tests/self_host:cross_compiler_parity_test` |
| Shuttle package boundary | `//tests/self_host:shuttle_compatibility_test` |

The differential target locks the current corpus dimensions: 697 lexer inputs,
32 bounded and 248 real declaration inputs, 47 bounded and 248 real definition
inputs, and two focused semantic inputs. It also validates the complete 248-
source compiler package through semantic construction. The C++ oracle
executables and their focused corpora are explicit test-repository inputs. The
audit processes independent cases concurrently and fails on the first
canonical-record difference.

Stage 49.4 transferred package lexer and parser authority to the self-hosted
frontend after the complete corpus passed. This target remains a regression
oracle; passing it does not return authority to the bootstrap implementation.

Stage 50.4 transferred package-symbol, canonical semantic type-identity,
import-binding, and declared type-name-resolution authority. Semantic records
must remain byte-identical across repeated, relocated, and parallel runs. The
cross-compiler and Shuttle package-scale targets are tagged `exclusive` so
their timing cannot be distorted by concurrent CPU-heavy work.

The Shuttle target builds x86-64 twice, requires byte-identical warm outputs,
runs the native result, checks wasm32, and proves a failed rebuild cannot
replace completed artifacts. Its staged path contains spaces to retain the
Windows quoting contract.

## Audit commands

```sh
bazel test //tests:presubmit
bazel test //tests:full --jobs=8 --nocache_test_results
bazel test //tests/self_host:cross_compiler_parity_test
bazel test //tests/self_host:shuttle_compatibility_test
bazel test //tests/self_host/tools/driver:tests
```

The timeout probe is deliberately excluded from suites because success means
Bazel terminates it and reports `TIMEOUT`:

```sh
bazel test //tests/self_host:timeout_probe_test \
  --test_timeout=1 --nocache_test_results
```
