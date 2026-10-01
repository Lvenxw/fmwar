# AGENTS.md

Guidance for coding agents working in this repository.

`FMWar` is a single-module Gradle plugin (`cn.mgtown.FMWar`) targeting Paper `1.21.11` on Java 21. Java sources live under `src/main/java`, bundled resources such as `plugin.yml` under `src/main/resources`. The build uses the Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`) with the `xyz.jpenilla.run-paper` plugin.

## Build and test

- Build: `./gradlew build` (Windows: `.\gradlew.bat build`)
- Run a dev server: `./gradlew runServer`
- Dependency and plugin resolution are mirrored through Aliyun and Huawei Cloud by default; `useChinaMirrors=false` in `gradle.properties` switches back to the official repositories. The PaperMC repository has no China mirror and is always queried.

## Agent skills

### Issue tracker

Issues and specs are tracked as local markdown files under `.scratch/<feature-slug>/`; no hosted tracker is in use. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical roles are used verbatim: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. In a local-markdown tracker they are written as the `Status:` line of each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` plus `docs/adr/` at the repo root. See `docs/agents/domain.md`.
