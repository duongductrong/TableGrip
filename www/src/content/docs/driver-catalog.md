---
title: Driver catalog & licenses
description: How the driver catalog and its lockfile work, and the build checks that keep the installer free of drivers.
---

No JDBC driver ships with TableGrip; users download them at runtime (see [Drivers](/docs/drivers)). This page is for
contributors who change the catalog or add a dependency.

## The catalog and its lockfile

- The **catalog** of downloadable drivers is defined in `core/build.gradle.kts`.
- `:core:lockDrivers` resolves it and writes
  `core/src/main/resources/dev/tablegrip/core/drivers/drivers.lock.json`, with the **SHA-256 of every jar**.
- At runtime, every downloaded jar must match its pinned hash, whether it comes from Maven Central, a mirror, or
  **Import from folder…**.

After changing the catalog, regenerate the lockfile and commit it:

```bash
./gradlew :core:lockDrivers
```

### Which drivers may be in the catalog

The catalog only takes jars whose license a person has reviewed and the allowlist accepts: **permissive** licenses,
or **LGPL** as a separate user download. GPL drivers such as MySQL Connector/J are never in the catalog; users choose
their own jar.

## Build checks

Three checks, all part of `./gradlew check`, keep this honest:

| Task | Fails when |
| --- | --- |
| `:app:verifyNoBundledDrivers` | A jar the app ships registers a `java.sql.Driver`. Also runs before every `package*` task. |
| `:app:thirdPartyNotices` | A shipped library's license hasn't been reviewed into `bundledLicenses` in `app/build.gradle.kts`. It also writes `THIRD-PARTY-NOTICES.txt` into the app. |
| `:core:verifyDriverLock` | `drivers.lock.json` is stale compared with the catalog. |

### When `check` fails

- **`verifyNoBundledDrivers`:** a new dependency pulled a driver onto the runtime classpath. Exclude it, or make it
  `compileOnly` / test-only.
- **`thirdPartyNotices`:** you added a library. Review its license and add it to `bundledLicenses`.
- **`verifyDriverLock`:** run `./gradlew :core:lockDrivers` and commit the result.

## Engines for non-JDBC databases

Redis and MongoDB use their own clients (Lettuce and the MongoDB Java driver), downloaded like catalog drivers.
TableGrip's adapter for each lives in `engines/redis` and `engines/mongodb`, ships inside the app as
`engines/<name>.jar` (outside the classpath), and is loaded together with the client's jars in its own class loader.
