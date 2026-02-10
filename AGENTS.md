# Repository Guidelines

## Project Structure & Module Organization
This is a multi-module Maven project. Key modules live at the repo root:

- `assistant-agent-core`: core execution engine (GraalVM executor, tool registry)
- `assistant-agent-extensions`: extensions (dynamic, experience, learning, search, reply, trigger, evaluation)
- `assistant-agent-prompt-builder`: dynamic prompt assembly
- `assistant-agent-evaluation`: evaluation engine
- `assistant-agent-common`: shared utilities/constants
- `assistant-agent-autoconfigure`: Spring Boot auto-configuration
- `assistant-agent-start`: application entry point

Each module uses standard Maven layout: `src/main/java`, `src/main/resources`, and (where present) `src/test/java`. Docs live in `docs/`, and diagrams/assets are in `images/`.

## Build, Test, and Development Commands
Run from the repo root unless noted.

- `mvn clean install -DskipTests`: build all modules without tests.
- `mvn test`: run the full test suite.
- `mvn test -Dtest=ClassName`: run a specific test class.
- `mvn test jacoco:report`: generate a coverage report.
- `cd assistant-agent-start; mvn spring-boot:run`: run the application.
- `export DASHSCOPE_API_KEY=...`: required for DashScope-backed flows (configure in `assistant-agent-start/src/main/resources/application.yml`).

## Coding Style & Naming Conventions
- Follow Google Java Style; 4-space indentation; max line length 120.
- Public APIs require Javadoc.
- Package root is `com.alibaba.assistant.agent`.
- Logging format should include class + method and a reason, e.g. `ClassName#method - reason=...`.
- All source files must carry the Apache 2.0 license header.

## Testing Guidelines
- Unit tests are required for new features.
- Target coverage for new code is > 60%.
- Test names should be descriptive, e.g. `shouldReturnSuccessWhenValidInput`.
- Place tests under `src/test/java` in the relevant module.

## Commit & Pull Request Guidelines
- Use Conventional Commits (seen in history): `feat(...)`, `fix(...)`, `docs(...)`, `chore(...)`, etc. Examples:
  - `feat(search): use Baidu Qianfan search API`
  - `fix(config): correct context binding path`
- PRs should include: description, linked issue, test updates, and any needed docs.
- Before submitting: build cleanly, tests pass, public APIs documented, and no secrets committed.

## Security & Configuration Tips
- Do not commit API keys or `.env` files.
- Prefer environment variables for secrets; configure defaults in `assistant-agent-start/src/main/resources/application.yml`.
