"""Check inference backend packaging, ELF dependencies and 16 KiB load alignment."""
import argparse, json, struct, zipfile

def elf_info(blob):
    assert blob[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
    offset = struct.unpack_from('<Q', blob, 32)[0]
    size, count = struct.unpack_from('<HH', blob, 54)
    headers = [struct.unpack_from('<IIQQQQQQ', blob, offset + i * size) for i in range(count)]
    loads = [h for h in headers if h[0] == 1]
    assert loads and all(h[7] >= 16384 for h in loads), 'ELF load segment is not 16 KiB aligned'
    dynamic = next(h for h in headers if h[0] == 2)
    entries = [struct.unpack_from('<qQ', blob, n) for n in range(dynamic[2], dynamic[2] + dynamic[5], 16)]
    strings = next(value for tag, value in entries if tag == 5)
    segment = next(h for h in loads if h[3] <= strings < h[3] + h[5])
    table = segment[2] + strings - segment[3]
    needed = []
    for tag, value in entries:
        if tag == 1:
            start = table + value
            needed.append(blob[start:blob.index(b'\0', start)].decode('ascii'))
    return needed

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    args = parser.parse_args()
    proof = {}
    system = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so'}
    with zipfile.ZipFile(args.apk) as apk:
        libraries = {}
        for name in apk.namelist():
            parts = name.split('/')
            if len(parts) == 3 and parts[0] == 'lib' and parts[2].endswith('.so'):
                libraries.setdefault(parts[1], {})[parts[2]] = name
        for abi, files in libraries.items():
            addons = {'libpinyin.so', 'libpinyinhelper.so', 'libpunctuation.so', 'libtable.so', 'libchttrans.so',
                      'libclipboard.so', 'libfullwidth.so', 'libimselector.so', 'libluaaddonloader.so',
                      'libquickphrase.so', 'libscel2org5.so', 'libspell.so', 'libunicode.so'}
            assert addons <= files.keys(), (abi, 'Missing built-in addon', addons - files.keys())
            expected = {'libqwen-completion.so', 'libfeinianyu-inference.so', 'libllama.so', 'libggml.so', 'libggml-base.so'}
            assert expected <= files.keys(), (abi, 'Missing inference library', expected - files.keys())
            if abi == 'arm64-v8a':
                variants = ['android_armv8.0_1', 'android_armv8.2_1', 'android_armv8.2_2', 'android_armv8.6_1',
                            'android_armv9.0_1', 'android_armv9.2_1', 'android_armv9.2_2']
                expected.update('libggml-cpu-' + variant + '.so' for variant in variants)
            else:
                expected.add('libggml-cpu.so')
            assert expected <= files.keys(), (abi, 'Missing CPU fallback or optimized variant', expected - files.keys())
            dependencies = {}
            for name in sorted(expected):
                required = elf_info(apk.read(files[name]))
                missing = set(required) - files.keys() - system
                assert not missing, (abi, name, 'Unpackaged dependency', missing)
                dependencies[name] = required
            proof[abi] = {'builtin_addons_retained': True, 'cpu_backends': sorted(n for n in expected if n.startswith('libggml-cpu')),
                          'elf_16k_alignment': True, 'dependencies': dependencies}
    assert proof, 'No native libraries found'
    print(json.dumps(proof, indent=2))

if __name__ == '__main__':
    main()
