# Unicode command-line arguments across all targets

Status: open. Cross-platform requirement confirmed 2026-10-30.

Windows, Linux and macOS are all required targets. Preserve one Cloth contract,
`main(string[] args)`, with the necessary native adapters for each platform.
Completing a Windows fix alone does not establish support on the other targets.

## Reproduced gap

The chunk 8 review passed `é中😀` as an argument on the current Windows host.
Comparing `args[0]` against the identical source literal returned status 1; the
ASCII control returned 0. The current adapter treats native narrow argv bytes
as Cloth UTF-8 without conversion.

## Proposed implementation approach

- Windows: receive Unicode arguments through a wide-character startup path,
  such as `wmain`, and convert to Cloth UTF-8 storage. Ensure the native entry
  point and linker/CRT startup settings agree for supported subsystems and
  runtime-linkage configurations.
- Linux/macOS: retain ordinary native argc/argv startup and handle its byte
  strings according to the agreed encoding policy. Do not route these targets
  through Windows APIs or assume all incoming bytes are valid UTF-8.
- Keep the existing portable Cloth main signatures, argument order, exclusion
  of the executable name, exit-status rules and storage lifetime guarantees.

These adapter details are proposals, not completed implementation.

## Open policy

Decide how to handle malformed Unicode or non-UTF-8 argument bytes. A controlled
startup error has been recommended but not confirmed by the user. Do not silently
settle replacement, locale conversion, rejection or a raw-byte API while adding
platform adapters.

## Verification required

- [ ] Implement the shared argument-encoding contract on all three targets.
- [ ] Execute native tests on Windows, Linux and macOS; distinguish verified
  execution from source inspection or IR verification.
- [ ] Cover ASCII, accented text, CJK and supplementary Unicode characters.
- [ ] Cover no arguments, empty arguments, spaces, quoting and multiple arguments.
- [ ] Verify argument order and preservation of the original text.
- [ ] Cover invalid input after its policy has been confirmed.
- [ ] Keep existing main forms and native exit statuses working on each target.
- [ ] Verify buffer cleanup and Windows startup/linking configurations.

Track the independent printing/formatting guard in
[native-formatting-guards.md](native-formatting-guards.md).
