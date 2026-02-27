# Repository Guidelines

## Project Structure & Module Organization
This repository is a Maven multi-module Java 17 project rooted at `pom.xml`.
Main modules in the workspace:
- `assistant-agent-common`: shared constants, enums, and tool abstractions
- `assistant-agent-core`: execution engine and tool registry bridge
- `assistant-agent-extensions`: dynamic tools, search, learning, reply, trigger
- `assistant-agent-prompt-builder`, `assistant-agent-evaluation`: prompt and evaluation pipelines
- `assistant-agent-autoconfigure`: Spring Boot auto-configuration
- `assistant-agent-start`: runnable demo app (`src/main/resources/application.yml`)

Code lives in `src/main/java`; tests in `src/test/java`; resources in `src/main/resources`; planning docs in `docs/plans`.

## Build, Test, and Development Commands
- `mvn clean install -DskipTests`: full reactor build without tests
- `mvn test`: run all tests
- `mvn -pl assistant-agent-start spring-boot:run`: run local demo app
- `mvn -pl assistant-agent-start -am -DskipTests compile`: compile starter plus required dependencies
- `mvn -pl assistant-agent-start test`: run starter-module tests only

Use module-scoped commands during incremental migration work.

## Coding Style & Naming Conventions
- Follow Google Java Style, line length <= 120. Framework modules use 4 spaces; enterprise modules use tabs
- Naming: classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`
- Keep package prefix under `com.alibaba.assistant.agent.*`
- Add Apache 2.0 license header to new Java files
- Public APIs should include Javadoc

## Testing Guidelines
- Test stack: JUnit 5 and Spring Boot test support
- Place tests under `<module>/src/test/java`
- Name test classes `*Test` (example: `LegacyAssistantBridgeServiceTest`)
- Prefer behavior-focused names like `shouldReturnResultWhenInputValid`
- If behavior changes, include or update tests in the same PR

## Commit & Pull Request Guidelines
- Use Conventional Commit prefixes: `feat`, `fix`, `hotfix`, `chore`, `docs`, `test`
- Keep commit subject concise and scoped (example: `feat(search): add provider X`)
- PRs should include purpose, affected modules, linked issue, and test evidence (`mvn test` output)
- Call out configuration changes explicitly

## Security & Configuration Tips
- Do not commit secrets
- Provide `DASHSCOPE_API_KEY` via environment variables
- Keep environment-specific values in local config overrides, not committed defaults
