"""Link and execute the fixtures emitted by UnicodeArgumentsTest on Linux/macOS.

Run from the repository root after that test:
    python3 Compiler/src/test/resources/unicode-arguments/verify-unix.py
An optional first argument selects another fixture directory.
"""
import pathlib
import subprocess
import sys

platform = 'macos' if sys.platform == 'darwin' else 'linux'
if sys.platform not in ('darwin', 'linux'):
    raise SystemExit('Run this verification on native Linux or macOS')
root = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[5] / 'build' / 'unicode-startup'
for name in ('arguments', 'noargs', 'trap'):
    subprocess.run(['cc', str(root / f'{platform}-{name}.o'), '-o', str(root / f'{platform}-{name}')], check=True)

checks = 0


def run(name, args, status, output=b''):
    global checks
    result = subprocess.run([bytes(root / f'{platform}-{name}')] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
    assert (result.returncode, result.stdout) == (status, output), (name, args, result.returncode, result.stdout)
    checks += 1


run('arguments', [text.encode('utf-8') for text in ['é中😀', '', 'two words', 'a"b\\', 'é', 'é']], 0)
valid = ['', 'é中😀', '\u0001\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff', '\U00010000\U0010ffff', '�', '\ufeff']
malformed = ['80', 'bf', 'c080', 'c1bf', 'c2', 'c27f', 'dfc0', 'e09fbf', 'e0a0', 'e180', 'eda080', 'edbfbf', 'efbf', 'f08fbfbf', 'f09080', 'f1807f80', 'f4908080', 'f5808080', 'f8', 'ff', '41f0908041', 'c28080']
for name in ('noargs', 'trap'):
    run(name, [], 77)
    run(name, [s.encode('utf-8') for s in valid], 77)
    for bad in malformed:
        run(name, [b'valid', bytes.fromhex(bad), b'\xff'], 1, b'Invalid Unicode in command-line argument 2 (expected UTF-8)\n')
    run(name, [b'\xff', b'\xff'], 1, b'Invalid Unicode in command-line argument 1 (expected UTF-8)\n')
print(f'Native {platform} startup: {checks} checks passed')
