# AfterDeath

Custom Paper plugin for a configurable Headsteal world. Java 21; intended for Paper 1.21.8 API and compatible recent Paper 1.21.x builds. Your server's exact Paper version must be tested before production use.

## Build

The project uses Gradle and downloads the Paper API from Paper's Maven repository. From the project root run:

```bash
gradle --no-daemon clean build
```

The plugin JAR will be in `build/libs/AfterDeath-1.0.0.jar`.

A GitHub Actions workflow is included at `.github/workflows/build.yml`. Upload/push this source into a GitHub repository, then open **Actions → Build AfterDeath → Run workflow**. After a successful run, download the `AfterDeath-plugin` artifact. The artifact is a ZIP containing the JAR.

## Setup

1. Install the JAR into `plugins/` and restart the server.
2. Stand in the intended Headsteal world and run `/afterdeath setworld`.
3. Run `/afterdeath wand`; left-click a block for position 1 and right-click a block for position 2. Both positions must be in the configured Headsteal world.
4. Optionally stand at the Headsteal spawn and run `/afterdeath setspawn`.
5. Restart or use `/afterdeath reload` after editing config.

## Commands

- `/afterdeath wand`
- `/afterdeath setworld`
- `/afterdeath setspawn`
- `/afterdeath reload`
- `/afterdeath revive <player>`
- `/afterdeath status <player>`

Admin commands require `afterdeath.admin` (OP by default). `afterdeath.bypass` bypasses elimination restrictions.

## Important testing notes

This source has not been compiled or tested against a live Paper server in the current environment. Build it first and test on a staging server before production use. Configure and test the Ban Box carefully. Keep a server backup before installing custom plugins.
