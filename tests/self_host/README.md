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
| Production check streams and status | `//tests/self_host/tools/driver:tests` |
| Syntax storage and trees | `//tests/self_host/tests:syntax_*_test` |
| GC and depth modes | `//tests/self_host/tests:extended` |
| Declaration failure modes | `//tests/self_host/tools/declaration_failure:failure_tests` |
| Package frontend failures | `//tests/self_host/tools/frontend_failure:failure_tests` |
| Syntax storage failures | `//tests/self_host/tools/syntax_storage_failure:failure_tests` |
| Syntax tree failure modes | `//tests/self_host/tools/syntax_tree_failure:failure_tests` |
| Four canonical record writers | `//tests/self_host/tools/*_records` |
| C++ differential regression audit | `//tests/self_host:cross_compiler_parity_test` |
| Shuttle package boundary | `//tests/self_host:shuttle_compatibility_test` |

The differential target locks the current corpus dimensions: 614 lexer inputs,
32 bounded and 188 real declaration inputs, and 43 bounded and 188 real
definition inputs. The C++ oracle executables and their focused
corpora are explicit test-repository inputs. The audit processes cases concurrently
and fails on the first canonical-record difference.

Stage 49.4 transferred package lexer and parser authority to the self-hosted
frontend after the complete corpus passed. This target remains a regression
oracle; passing it does not return authority to the bootstrap implementation.

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
