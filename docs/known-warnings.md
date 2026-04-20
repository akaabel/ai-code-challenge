# Known Startup Warnings

Warnings printed on `clj -M:dev dev` that are **harmless and expected**. Tracked here so they don't get mistaken for new bugs, and so we remember which ones we chose to fix vs. live with.

## 1. `Unsafe::arrayBaseOffset` from Agrona

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::arrayBaseOffset has been called by org.agrona.UnsafeAccess
        (file:/Users/aka/.m2/repository/org/agrona/agrona/1.16.0/agrona-1.16.0.jar)
```

- **Cause:** Agrona 1.16.0 (transitive dep via XTDB 1.24.5) uses a deprecated `Unsafe` API that JDK 25 warns about.
- **Impact:** None at runtime. Warning only.
- **Fix:** Upstream only — Agrona maintainers must migrate. Revisit if we upgrade XTDB or move to XTDB 2.x.

## 2. `System::loadLibrary` from RocksDB

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::loadLibrary has been called by org.rocksdb.NativeLibraryLoader
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
```

- **Cause:** RocksDB loads its JNI native library. JDK 22+ requires explicit opt-in via `--enable-native-access`.
- **Impact:** None at runtime. Warning only. Future JDK releases may make it an error.
- **Fix (available, deferred):** Add `"--enable-native-access=ALL-UNNAMED"` to `:jvm-opts` in both the `:dev` and `:prod` aliases in `deps.edn`. One-line change. Deliberately deferred to keep Step 1 minimal; revisit as a drive-by cleanup or when a JDK upgrade makes it a hard error.

## 3. `Browserslist: caniuse-lite is outdated`

```
Browserslist: caniuse-lite is outdated. Please run:
  npx update-browserslist-db@latest
```

- **Cause:** Emitted from inside the bundled Tailwind standalone binary (v3.4.17) — the caniuse dataset is compiled in.
- **Impact:** None. Cosmetic.
- **Fix:** None from our side; we don't use npm in this project. Goes away if/when we upgrade the Tailwind binary.

---

Update this file when warnings change, disappear, or when we decide to fix one.
