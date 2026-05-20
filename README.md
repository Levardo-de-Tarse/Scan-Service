# Scanner Service

Spring Boot 3 REST service that runs **[NAPS2](https://www.naps2.com/)** in console mode to scan from TWAIN/WIA (and other drivers NAPS2 supports), produce PDFs, and expose results over HTTP.

| | |
|--|--|
| **Stack** | Java **17**, Spring Boot **3.2**, Maven |
| **Default port** | `8080` (`server.port`) |
| **Default output** | `%USERPROFILE%\ScannerService\scans` (see `scanner.output.dir`) |

## Requirements

- **Development** (`mvn spring-boot:run`): JDK **17+**
- **Portable Windows `.exe`**: JDK **17+** on the build machine only (`jpackage`; **WiX not required** for `--type app-image`)
- **NAPS2** installed on each PC **or** copied into `naps2-bin` when you package with `-Dnaps2.bundle=true` ([GPL-2.0 — read `LEGAL-NAPS2.md`](./LEGAL-NAPS2.md))
- **Scanner drivers** (TWAIN/WIA, etc.) installed in Windows for your hardware

## How NAPS2 is found

If `scanner.command` is the default token `naps2` (or `naps2.console` / `naps2.console.exe`), the service resolves the executable in this order:

1. **`naps2-bin\NAPS2.Console.exe`** next to the packaged app (jpackage / portable layout)
2. **`C:\Program Files\NAPS2\NAPS2.Console.exe`**, then **`Program Files (x86)`**, then **`%LOCALAPPDATA%\Programs\NAPS2\`**
3. Otherwise the token is passed to `ProcessBuilder` (depends on **PATH**)

Override anytime: **`scanner.command`** (full path to `NAPS2.Console.exe`), or env **`SCANNER_COMMAND`** (Spring relaxed binding).

## Configuration

Defined in [`src/main/resources/application.properties`](./src/main/resources/application.properties). Common overrides:

| Property | Default | Purpose |
|----------|---------|---------|
| `server.port` | `8080` | HTTP port |
| `scanner.command` | `naps2` | NAPS2 console executable (path or token; see above) |
| `scanner.driver` | `auto` | `auto`: merge devices from all ids in `scanner.auto-drivers` and pick driver per scan; or set a fixed id (`twain`, `wia`, `escl`, …) |
| `scanner.auto-drivers` | `twain,wia,escl` | Order of drivers probed when `scanner.driver=auto` |
| `scanner.device-list-cache-seconds` | `15` | How long to reuse the device list (avoids slow `/devices` on every browser refresh). `0` = always re-query NAPS2 |
| `scanner.output.dir` | `${user.home}/ScannerService/scans` | Directory for PDFs when the request omits `directory` |

**Environment variables** (Spring Boot relaxed binding): e.g. `SERVER_PORT`, `SCANNER_COMMAND`, `SCANNER_DRIVER`, `SCANNER_AUTO_DRIVERS`, `SCANNER_DEVICE_LIST_CACHE_SECONDS`, `SCANNER_OUTPUT_DIR`.

**CLI / IDE:** `--scanner.command=...`, `--scanner.driver=...`, `--scanner.output.dir=...`

*Optional local file (not in Git if you use the repo `.gitignore`): `application-local.properties`.*

## Build and run

```bash
./mvnw test
./mvnw package
./mvnw spring-boot:run
```

**Windows (PowerShell)** — Maven разбивает `spring-boot.run.arguments` **по пробелам**, поэтому путь вида `C:\Program Files\...` превращается в `C:\Program` и NAPS2 не запускается. Надёжные варианты:

```powershell
# 1) Переменная окружения (лучше всего для путей с пробелами)
$env:SCANNER_COMMAND = 'C:\Program Files\NAPS2\NAPS2.Console.exe'
.\mvnw.cmd spring-boot:run

# 2) Короткое имя каталога (без пробелов в одном токене)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--scanner.command=C:\Progra~1\NAPS2\NAPS2.Console.exe"
```

Обычно **достаточно** просто `.\mvnw.cmd spring-boot:run`: для токена `naps2` сервис сам ищет `NAPS2.Console.exe` в `Program Files` и в `%LOCALAPPDATA%\Programs\NAPS2` (см. выше).

**Unix-like:**

```bash
SCANNER_COMMAND="/path/to/naps2.console" ./mvnw spring-boot:run
```

## Windows portable build (`.exe` + embedded JRE)

1. **Build machine:** Windows x64, **JDK 17+** with `jpackage` on `PATH` or **`JAVA_HOME`** set.
2. From Maven (runs `scripts/build-windows-portable.ps1` after `package`):

```powershell
# Portable app only (NAPS2 must exist on each target PC)
.\mvnw.cmd "-Pwindows-exe" package

# Also copy your local NAPS2 into dist\ScannerService\naps2-bin (GPL — LEGAL-NAPS2.md)
.\mvnw.cmd "-Pwindows-exe" "-Dnaps2.bundle=true" package

# Non-default NAPS2 folder to copy from
.\mvnw.cmd "-Pwindows-exe" "-Dnaps2.bundle=true" "-Dnaps2.bundle.source=D:\Apps\NAPS2" package
```

**Or** run the script directly (runs `mvn package` unless `-SkipMvn`):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows-portable.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows-portable.ps1 -Naps2Bundle true
```

Use `-SkipTests` to speed up packaging.

**Outputs** (ignored by Git via `dist/` in `.gitignore`):

| Artifact | Description |
|----------|-------------|
| `dist/ScannerService/` | Run **`ScannerService.exe`** (console window for logs) |
| `dist/ScannerService-windows-portable.zip` | Portable bundle **without** bundled NAPS2 |
| `dist/ScannerService-windows-with-naps2.zip` | When NAPS2 was copied into `naps2-bin/` |

Bundled layout still needs **scanner drivers** on each PC. Redistributing NAPS2 binaries requires **GPL compliance** (`LEGAL-NAPS2.md`). Unsigned builds may trigger SmartScreen.

**Runtime:** the packaged exe inherits normal OS environment variables (e.g. **`SERVER_PORT`** if you must avoid **8080**). The first cold start can take **30–60 seconds** before `http://localhost:<port>/api/scan/devices` responds — this is normal for an embedded JRE + Spring Boot. If NAPS2 was **not** bundled, the target PC still needs NAPS2 installed (or rebuild with `-Dnaps2.bundle=true`).

## API

Base path: **`/api/scan`**.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/scan` | Run a scan; returns JSON with paths and PDF bytes |
| `GET` | `/api/scan/devices` | List devices (**NAPS2 7.3+ / 8.x**). With `scanner.driver=auto`, each line is `driver||deviceName` (e.g. `twain||Canon …`). With a fixed `scanner.driver`, names are plain (no prefix). |
| `POST` | `/api/scan/default-device?deviceName=...` | Set in-memory default device name (until process restarts) |
| `GET` | `/api/scan/default-device` | Current default device label |
| `GET` | `/api/scan/download?filePath=...` | Download PDF (`Content-Disposition: attachment`). **`filePath` must be trusted** — treat as an internal/admin endpoint |

### `POST /api/scan`

**Request body** (all fields optional):

```json
{
  "filename": "scan.pdf",
  "directory": "C:/Users/You/ScannerService/scans",
  "scannerDevice": "twain||Canon MF743C or a unique substring of that name",
  "profile": "Exact NAPS2 GUI profile name"
}
```

| Field | Behavior |
|-------|----------|
| `directory` | Output folder; defaults to `scanner.output.dir` |
| `filename` | PDF name; default `scan_<timestamp>.pdf` |
| `profile` | If set: **`profile` wins** — NAPS2 uses `-p` (GUI profile). `scannerDevice` is ignored for that request |
| `scannerDevice` | **NAPS2 7.3+ / 8.x:** pass a **substring** that matches a line from `GET /devices` (auto mode resolves **twain vs wia** by the first matching `--listdevices` list), or pass the **full** `driver||name` string from `GET /devices` to disambiguate. With **fixed** `scanner.driver`, behavior is unchanged (plain partial name). **7.1.x:** GUI **profile** name (`-p`). If omitted, the server uses `POST /default-device`, else NAPS2 may use the last GUI profile — empty `{}` often fails on newer CLI |

**Success response** (`200`): `status`, `filePath`, `scannerDevice`, `fileContent` (**byte array → Base64 in JSON**).

**Error response** (`500`): `status` begins with `Error: …` (no body bytes).

### NAPS2 CLI capability

At startup the service runs `NAPS2 --help` and checks for **`listdevices`**.

- **Extended CLI (7.3+, 8.x):** `GET /devices` works; scanning can use `--noprofile` + `scannerDevice`.
- **Older (e.g. 7.1):** no device list; use **`profile`** or **`scannerDevice`** as the **GUI profile name**, or upgrade NAPS2.

**Note:** `default-device` is stored **only in JVM memory** — it is lost on restart.

## Tests

```bash
./mvnw test
```

## Git — what stays out of the repository

Do **not** commit build outputs, local scans, secrets, or IDE-only files. **`.gitignore`** already excludes the important ones, including:

| Pattern / path | Why |
|----------------|-----|
| `target/` | Maven build |
| `dist/` | Portable `.exe`, embedded JRE, ZIPs (large binaries) |
| `scans/` | Optional local scan output folder at repo root |
| `*.log`, `logs/`, JVM crash dumps | Runtime noise / confidential paths |
| `.env*`, `application-*-local.*` | Local secrets and overrides (commit **`application.properties`** without secrets only) |
| `.idea/` (partial), Eclipse/NetBeans noise, `Thumbs.db`, editor backups | Machine-specific |

Before `git push`, run `git status` and ensure you are not adding `dist/`, `target/`, personal `.env`, or generated PDFs.

## Legal

Bundling or redistributing NAPS2: **[`LEGAL-NAPS2.md`](./LEGAL-NAPS2.md)**.
