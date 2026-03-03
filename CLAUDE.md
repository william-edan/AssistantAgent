# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- **Java 17+** (OpenJDK or GraalVM)
- **Maven 3.8+**
- Environment variable `DASHSCOPE_API_KEY` for DashScope LLM access

## Build & Development Commands

```bash
# Full build (skip tests)
mvn clean install -DskipTests

# Compile a single module with dependencies
mvn -pl assistant-slot -am compile

# Run the demo app (no DB required)
mvn -pl assistant-agent-start spring-boot:run

# Run with migration profile (requires MySQL + Redis)
mvn spring-boot:run -pl assistant-agent-start -Dspring-boot.run.profiles=migration

# Run all tests
mvn test

# Run tests for a specific module
mvn -pl assistant-infra test

# Run a single test class (add failIfNoSpecifiedTests flag when using -am)
mvn test -pl assistant-runtime -am -Dtest=AssistantStateKeysTest -Dsurefire.failIfNoSpecifiedTests=false

# Run a single test method
mvn test -pl assistant-runtime -Dtest=AssistantStateKeysTest#allKeysAreDefined

# Install dependency modules before testing a downstream module
mvn install -pl assistant-infra,assistant-controlplane,assistant-slot -DskipTests && mvn test -pl assistant-runtime
```

**Gotcha**: When using `-Dtest=ClassName -am`, add `-Dsurefire.failIfNoSpecifiedTests=false` because upstream modules won't have that test class and Surefire will fail.

## Architecture Overview

This is a **Maven multi-module Java 17** project built on Spring Boot 3.4.8 + Spring AI 1.1.0 + Spring AI Alibaba 1.1.2.0. It implements a **Code-as-Action (CodeAct) Agent** that generates and executes Python code in a GraalVM sandbox to complete tasks.

### Two-Phase Agent Execution Model

1. **REACT Phase** — LLM decides intent, selects tools (`write_code`, `execute_code`, `write_condition_code`, or React-stage tools like `reply`)
2. **CODEACT Phase** — Sub-agent generates Python code, executes it in GraalVM sandbox, code calls registered tools (search, reply, trigger, etc.)

### Module Dependency Graph

**Framework modules** (stable):
```
assistant-agent-start (runnable app)
├── assistant-agent-autoconfigure (Spring Boot auto-config, DashScope)
│   ├── assistant-agent-core (GraalVM executor, tool registry bridge)
│   │   └── assistant-agent-common (CodeactTool interface, constants, hooks)
│   ├── assistant-agent-extensions (experience, learning, search, reply, trigger, evaluation, dynamic tools)
│   │   └── assistant-agent-common
│   ├── assistant-agent-prompt-builder
│   └── assistant-agent-evaluation
```

**Enterprise platform modules** (under active migration, `@Profile("migration")`):
```
assistant-api (REST controllers, protocol adapters)
└── assistant-runtime (AssistantAgentFactory, state keys)
    ├── assistant-slot (slot collection, enrichment, resolution, computed fields)
    │   └── assistant-controlplane (ToolMeta, AuditEvent, Identity/TokenBroker)
    ├── assistant-execution (DAG execution, HTTP step executor — in progress)
    │   └── assistant-controlplane
    └── assistant-infra (MysqlCheckpointSaver, Flyway migrations)
```

### Key Extension Points

- **CodeactTool** (`common/tools/CodeactTool.java`) — Core tool interface, extends Spring AI `ToolCallback`. Provides `ParameterTree`, `ReturnSchema`, `CodeactToolDefinition`.
- **DynamicCodeactToolFactory** (`extension/dynamic/spi/`) — SPI for creating tools dynamically (HTTP API from OpenAPI, MCP protocol).
- **Hook system** — Hooks implement `com.alibaba.cloud.ai.graph.agent.hook.Hook`, annotated with `@HookPhases` for REACT/CODEACT/ALL phase routing. Grouped via `HookPhaseUtils.groupByPhase()`.
- **SearchProvider** (`extension/search/spi/`) — SPI for pluggable knowledge/web/project search.
- **Auto-configuration** — Extensions register via `META-INF/spring.factories` in assistant-agent-extensions.

### Agent Construction Pattern

See `CodeactAgentConfig.java` — uses `CodeactAgent.builder()`:
- `.systemPrompt()` / `.model()` / `.language(Language.PYTHON)`
- `.codeactTools(List<CodeactTool>)` — tools available inside Python sandbox
- `.tools(ToolCallback[])` — React-phase tools
- `.hooks()` / `.subAgentHooks()` — phase-specific hooks
- `.saver(new MemorySaver())` — checkpoint saver for multi-turn conversations

### State Management

- `CodeactStateKeys` (`common/constant/`) — framework state keys for `OverAllState`
- `AssistantStateKeys` (`runtime/agent/`) — enterprise platform state keys (migration profile)
- State flows through `OverAllState` (a `Map<String, Object>`)
- `BaseCheckpointSaver` interface (`spring-ai-alibaba-graph-core`) — `put`/`get`/`list`/`release` methods, `Checkpoint.state` is `Map<String, Object>`
- `MemorySaver` — in-memory reference implementation (public methods are `final`, do not extend)

### Enterprise Slot Collection Architecture (assistant-slot)

The slot module uses a **port/adapter pattern** to avoid coupling to infrastructure:

- **Port interfaces** (`slot/port/`): `SystemAccessProfilePort` (system URL/auth config), `OptionCachePort` (pluggable cache, Redis or in-memory)
- **Adapter models** (`slot/model/`): `ToolMetaSnapshot` (replaces old `CapabilityRegistry`), `ResolverContext` (replaces old `ConversationContext`)
- **Core services**: `SlotSchemaParser` (parses slot_schema JSON, falls back to request_schema), `SlotCollectorService` (priority-based batch collection), `SlotEnricherService` (loads API options with cache + enumMapping fallback), `SlotResolverService` (value resolution via API)
- **Identity**: `TokenBroker` interface in controlplane, used by both SlotResolverService and SlotEnricherService for API authentication

### Control Plane (assistant-controlplane)

MyBatis-Plus entities and mappers for: `ToolMeta` (tool registry), `AuditEvent` (audit log), `IdentityBinding` + `TokenBroker`/`DefaultTokenBroker` (identity management).

### Database (assistant-infra)

Flyway migrations in `assistant-infra/src/main/resources/db/migration/` (V1-V6): tool_meta, identity_binding, token_lease, audit_event, checkpoint, system_access_profile.

### Configuration

- `application.yml` — default config, no DB needed, DashScope API key via `${DASHSCOPE_API_KEY}`
- `application-reference.yml` — full reference of all config options
- `application-migration.yml` — MySQL + Redis + Flyway for enterprise platform
- Config namespace: `spring.ai.alibaba.codeact.extension.*` (experience, learning, search, reply, evaluation, trigger)

### REST API Entry Points

Two mutually exclusive API surfaces controlled by Spring profiles:

- **Demo mode** (`!migration`): `UnifiedChatController` in `assistant-agent-start/src/main/java/.../start/chat/` — endpoints `GET /api/chat/stream`, `POST /api/chat/run_sse`, `POST /api/chat/resume_sse` (Spring WebFlux SSE streaming)
- **Enterprise mode** (`migration`): `assistant-api` module controllers + protocol adapters

### Migration Planning

- **Architecture design (latest)**: `docs/plans/2026-02-27-assistant-orchestration-design-v2.3.md` — final orchestration design, ReAct-as-planner, control plane at tool boundary
- **Execution checklist (latest)**: `docs/plans/2026-02-28-assistant-orchestration-v2.3-execution-checklist.md` — Phase 1-4 implementation tasks
- Architecture decisions: `docs/plans/2026-02-25-assistantagent-migration-conclusions.md`
- Original execution plan: `docs/plans/2026-02-25-execution-plan.md`

## Code Conventions

- **Style**: Google Java Style, 120 char line limit. Framework modules use 4-space indentation; new enterprise modules (`assistant-api`, `assistant-runtime`, `assistant-slot`, `assistant-execution`, `assistant-controlplane`, `assistant-infra`) use tabs
- **Package root**: `com.alibaba.assistant.agent.*`
- **Logging**: `logger.info("ClassName#methodName - reason=description, param={}", value)`
- **License header**: Apache 2.0 on all new Java files (Copyright 2024-2025)
- **Commits**: Conventional Commits — `feat:`, `fix:`, `hotfix:`, `chore:`, `docs:`, `test:`, `refactor:`
- **Test names**: behavior-focused — `shouldReturnResultWhenInputValid()`
- **Javadoc**: All public APIs must have Javadoc with `@param`, `@return`, `@throws`, `@since`
- **Profile isolation**: Use `@Profile("migration")` / `@Profile("!migration")` to prevent Bean conflicts between demo and enterprise modes. The start module's `MigrationScanConfiguration` handles `@ComponentScan` + `@MapperScan` for all new modules when migration profile is active.

## CI/CD

GitHub Actions workflow (`.github/workflows/build.yml`): triggers on PR and push to `main`/`master`/`develop`. Runs `mvn clean install -B -V` then `mvn test -B` with JDK 17 Temurin. Uploads surefire reports on failure.
