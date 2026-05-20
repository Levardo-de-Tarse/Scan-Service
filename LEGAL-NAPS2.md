# Bundling NAPS2

[NAPS2](https://www.naps2.com/) is a separate open-source project (license: **GNU General Public License v2.0**).

If you copy the NAPS2 binaries into this application’s distribution (the `naps2-bin` folder next to `ScannerService.exe`), you must comply with the GPL-2.0, including:

- Preserve copyright and license notices that ship with NAPS2.
- On request, provide corresponding source code or a written offer as required by the GPL-2.0.
- Ensure your obligations match how you distribute the combined package (this note is not legal advice).

Official source repository: https://github.com/cyanfish/naps2

## What bundling NAPS2 does *not* replace

TWAIN/WIA (and other) **scanner drivers** are installed in Windows and are **not** part of the NAPS2 folder you copy. Each PC still needs the vendor’s driver so the scanner appears in NAPS2.
