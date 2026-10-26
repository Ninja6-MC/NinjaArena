# Changelog

All notable changes to **NinjaArena** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

Nothing has been released yet. The plugin targets Paper 26.3, needs the server to run on
Java 25, and does not run on Folia.

### Added
- Initial project scaffolding with Gradle Kotlin DSL, the Gradle 9.8.1 wrapper and a
  Java 25 toolchain, compiling against `paper-api` 26.3.
- `NinjaArenaPlugin`, which only logs on enable and disable.
- `plugin.yml` with `api-version: '26.3'` and `folia-supported: false`, the
  `/ninjaarena` command with `/narena` as an alias, and the permission nodes
  `ninjaarena.play` (default true), `ninjaarena.host` and `ninjaarena.admin` (both op),
  where admin includes host and host includes play. The command has no executor yet and
  answers with its usage line.
- JUnit test that parses the built `plugin.yml` with paper-api's own
  `PluginDescriptionFile` and checks the main class, API version, version expansion,
  command, alias and permission tree, and reads `folia-supported: false` from the raw
  YAML.
- CI workflow running `Build and Test` on Java 25: a CRLF guard, Gradle wrapper
  validation, `./gradlew test` and `./gradlew build`.
- DCO and Standards workflows calling the org's reusable workflows, plus Dependabot,
  `.editorconfig`, `.gitattributes` and an empty `.github/standards-exceptions.yml`.
- OpenSSF Scorecard workflow, run weekly, on every push to `main` and when branch
  protection changes.
