# Repository Guidelines

This document describes how to contribute to this Maven-based Java service (intranetservices).
Keep changes small, tested, and documented.

## Project Structure & Module Organization
- `src/main/java` — application code
- `src/main/resources` — runtime resources
- `src/test/java` — unit/integration tests
- `config/` — environment config and templates
- `docs/` — design and operational docs
- `docker/`, `docker-compose.yaml` — container artifacts
- `target/` — build output (gitignored)
- `pom.xml`, `mvnw` — Maven build and wrapper

## Build, Test, and Development Commands
- `./mvnw clean package` — build artifact and run tests
- `./mvnw test` — run unit tests
- `./mvnw verify` — run full verification (integration, checks)
- `./mvnw -DskipTests package` — build without running tests
- `docker-compose up --build` — run services locally (when applicable)
- `java -jar target/*.jar` — run the built JAR

## Coding Style & Naming Conventions
- Follow standard Java conventions: 4-space indentation, UTF-8 files.
- Classes: `PascalCase` (e.g., `UserService`).
- Methods/variables: `camelCase` (e.g., `findById`).
- Constants: `UPPER_SNAKE_CASE`.
- Packages: lowercase dot-separated (e.g., `com.company.service`).
- Keep public APIs stable; add deprecation notes if changing behavior.
- Use project Checkstyle/formatter if configured; otherwise import IDE settings from repo where provided.

## Testing Guidelines
- Tests live in `src/test/java`.
- Unit test name suffix: `*Test`. Integration tests: `*IT`.
- Use JUnit (5) and mocks for unit tests; aim for clear, deterministic tests.
- Run: `./mvnw test`. Generate coverage if configured: `./mvnw test jacoco:report`.

## Commit & Pull Request Guidelines
- Commit style: imperative subject, short (e.g., `Add caching for user queries`).
- Branches: `feature/xyz`, `bugfix/issue-123`.
- PRs must include: short description, linked issue (if any), test instructions, and screenshots for UI changes.
- Keep PRs focused and include changelog entries when appropriate.

## Security & Configuration Tips
- Never commit secrets—use `config/` templates and environment variables.
- Use `docker/env` or `.env` for local secrets and add `.env` to `.gitignore`.

For questions or onboarding, see `docs/` or open an issue.
