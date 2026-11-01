# Windows x86 usize validation

Step 6 target: `i686-pc-windows-msvc`, selected by the user on 2026-11-01.
Validation ran real PE32 executables on the Windows x64 host, using installed
x86 MSVC CRT and Windows SDK libraries. This establishes Windows x86 execution;
it does not establish 32-bit Linux/macOS/ARM execution or a 32-bit compiler JVM.

## Target and ABI

- Native pointer, size_t, usize, C int and C long: 32 bits. C long long and
  explicit int<64>: 64 bits. C double: IEEE binary64.
- LLVM 22.1.8, CPU `generic`, features `+sse2`, `LLVMCodeGenLevelDefault`,
  `LLVMRelocPIC`, `LLVMCodeModelDefault`. There is no added LLVM IR optimization
  pipeline. Object and assembly emission use the same target-machine settings.
- SSE2 is the Windows x86 baseline, preserving the existing float32/float64
  rounding contract. A native test detects excess x87 precision with
  float32(16,777,216) + 1. Microsoft also documents SSE2 as its default x86
  instruction set: [MSVC /arch](https://learn.microsoft.com/en-us/cpp/build/reference/arch-x86?view=msvc-170).
- CRT calls and native startup use the C calling convention. WideCharToMultiByte
  declarations and calls use LLVM x86_stdcallcc, including validation for main
  without an argument parameter. The call uses CP_UTF8 and WC_ERR_INVALID_CHARS.
  See [Win32 stdcall](https://learn.microsoft.com/en-us/cpp/cpp/stdcall?view=msvc-170)
  and [WideCharToMultiByte](https://learn.microsoft.com/en-us/windows/win32/api/stringapiset/nf-stringapiset-widechartomultibyte).
- The toolchain resolver selects lib/x86, SDK ucrt/x86 and um/x86, /machine:x86,
  and Hostx64/x86/link.exe for the MSVC linker on this host. Console startup
  discovers wmain; GUI startup selects wmainCRTStartup explicitly.

## Reproduce

Run from the repository root with the x86 Visual Studio CRT/SDK installed:

```powershell
.\gradlew.bat :Compiler:run --args="--target i686-pc-windows-msvc examples/USizeX86.co build/usize-x86/USizeX86" --offline --console=plain
& .\build\usize-x86\USizeX86.exe "é😀" ""
.\gradlew.bat :Compiler:test --tests cloth.WindowsX86Test --tests cloth.BufferCountsTest --tests cloth.UnicodeArgumentsTest --offline --console=plain
```

The example prints 32 and PEC: 0. The tests inspect executable headers for
machine 0x14c and PE32 magic 0x10b, so a successful x64 executable cannot satisfy
the execution check. The CLI target option selects an already supported ABI;
linking still requires tools and libraries matching that target.

WindowsX86Test writes reproducible inspection artifacts under build/usize-x86:
USizeX86.ll, USizeX86.s and USizeX86.obj. These generated files are not source
fixtures; the source is [USizeX86.co](../../../../examples/USizeX86.co).

## Observed assembly

The native-width next function was emitted as:

```asm
_cloth.fn.0.next:
    movl 4(%esp), %eax
    incl %eax
    retl
```

The explicit unsigned int<64> increment uses both low and high words. Selected
instructions from its body, omitting the stack frame and parameter stores:

```asm
    movl 8(%ebp), %eax
    movl 12(%ebp), %edx
    addl $1, %eax
    adcl $0, %edx
```

The bounds-checked int array read compares its native index and outer count with
cmpl/jae, then loads with movl (%eax,%edx,4), %eax. The UTF-8 scalar loop uses
incl on its byte index, addl on its scalar count and cmpl/jb for iteration. IR
confirms i32 count/index PHIs and i32 malloc/memcpy sizes, while the explicit
wide function retains i64 input/output. The recursive string-array copy helper
checks count * 8 in i32, loops with an i32 index and calls the native byte-copy
helper with i32 sizes. Object inspection reports coff-i386.

These are observations for this source and code-generation configuration, not
fixed register-allocation or timing promises. Re-inspect generated output when
changing LLVM, code-generation settings, ABI lowering or representative loops.

## Execution coverage

- Scalar integers, usize wrapping, explicit signed/unsigned 64-bit arithmetic
  and division, 64-bit float conversions, float16/32/64 calls and formatting,
  C pow and float32 rounding.
- Independent arrays and returned strings, aligned int64 element storage,
  Unicode scalar sizes, metadata identity/width/rank/extent, maximum static
  extents after empty axes, and rejection of extents above usize::max.
- Static/dynamic CRT, bundled LLD and MSVC LINK, console/GUI startup, parameterless
  main, Unicode argument arrays, signed narrow and wide process return values.
- Malformed UTF-16 before both main forms, first-invalid-argument termination,
  and balanced allocation/free tracking for converted and copied arguments.
- Controlled wide/signed index, numeric cast, division, metadata, rectangular
  shape, multiplication, concatenation and allocation-header overflow failures.
  Allocation-guard fixtures use invalid pointers and must fail before access;
  boundary byte arithmetic succeeds without allocating huge objects.

Step 6 verification: `gradlew.bat :Compiler:test --offline --console=plain` passed
all 621 tests across 45 suites with zero failures/errors/skips. Whitespace and
skill/documentation link checks passed.

Step 7 completed integration with the same parsed program executed on Windows
x64, Windows x86 and x64 again, plus CLI/runtime first-error cases on both widths.
The final full suite passed 624 tests across 46 suites with zero failures/errors/
skips. The portable examples/USize.co also compiled and ran through native x64
and explicit x86 CLI builds, printing 1, usize and PEC: 0 on each.
