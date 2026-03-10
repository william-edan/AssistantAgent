# Enterprise Control Plane Phase 2 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add the Phase 2 enterprise control-plane models for query, check, action, and interaction definitions so the new platform can represent read resolvers, user-facing query actions, precondition checks, atomic write actions, and interaction policies without relying on `tool_meta`.

**Architecture:** Phase 2 remains additive. New Flyway migrations create the control-plane tables for resolver/query/check/action/interaction definitions, and `assistant-controlplane` gains the corresponding entity/mapper/service skeletons plus contract tests. No runtime compiler or execution wiring changes in this batch; these objects only establish the storage and service baseline for later workflow compilation.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Flyway, JUnit 5, Mockito, Maven.

---

### Task 1: Add Phase 2 contract tests for definition services

**Files:**
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/query/ReferenceResolverServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/query/BusinessQueryActionServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/query/PreconditionCheckServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/action/ActionSpecServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/interaction/InteractionSpecServiceContractTest.java`

**Step 1: Write the failing tests**

Write contract tests for these minimal behaviors:
- `ReferenceResolverService.findLatestEnabledByCode(spaceId, resolverCode)` ignores blank codes and delegates to `getOne(...)`.
- `BusinessQueryActionService.findLatestEnabledByCode(spaceId, queryActionCode)` ignores blank codes and delegates to `getOne(...)`.
- `PreconditionCheckService.findLatestEnabledByCode(spaceId, checkCode)` ignores blank codes and delegates to `getOne(...)`.
- `ActionSpecService.findLatestEnabledByCode(spaceId, actionCode)` ignores blank codes and delegates to `getOne(...)`.
- `InteractionSpecService.findLatestEnabledByCode(spaceId, interactionCode)` ignores blank codes and delegates to `getOne(...)`.

**Step 2: Run tests to verify they fail**

Run: `mvn -pl assistant-controlplane "-Dtest=ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest" test`
Expected: build fails because the new production classes do not exist yet.

**Step 3: Write minimal implementation**

Add the new entities, mappers, and services with only the fields and query helpers needed by the contract tests. Reuse the service style already used in `ToolMetaService`, `PlatformSpaceService`, and `ConnectorService`.

**Step 4: Run tests to verify they pass**

Run: `mvn -pl assistant-controlplane "-Dtest=ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add phase2 definition services"
```

### Task 2: Add Flyway migrations for Phase 2 definition tables

**Files:**
- Create: `assistant-infra/src/main/resources/db/migration/V16__create_reference_query_action_tables.sql`
- Create: `assistant-infra/src/main/resources/db/migration/V17__create_action_and_interaction_tables.sql`

**Step 1: Add migration SQL**

Create the two SQL files exactly as defined in `docs/plans/2026-03-10-enterprise-physical-ddl-java-model-and-migration-plan.md`, including indexes and version columns.

**Step 2: Run module compile to verify the resources are accepted**

Run: `mvn -pl assistant-infra -DskipTests compile`
Expected: PASS.

**Step 3: Commit**

```bash
git add assistant-infra/src/main/resources/db/migration
git commit -m "feat(infra): add phase2 enterprise definition migrations"
```

### Task 3: Add minimal Java entity and mapper skeletons for Phase 2 models

**Files:**
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/ReferenceResolver.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/ReferenceResolverService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/BusinessQueryAction.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/BusinessQueryActionService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/PreconditionCheck.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/PreconditionCheckService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/mapper/ReferenceResolverMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/mapper/BusinessQueryActionMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/mapper/PreconditionCheckMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/action/ActionSpec.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/action/ActionSpecService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/action/mapper/ActionSpecMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/interaction/InteractionSpec.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/interaction/InteractionSpecService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/interaction/mapper/InteractionSpecMapper.java`

**Step 1: Implement only the fields used by Phase 2 baseline queries**

Each entity should include MyBatis-Plus annotations, audit timestamps, version, status, and the key schema/config JSON columns defined in the physical DDL draft.

**Step 2: Implement minimal service contracts**

Expose only these query helpers:
- `ReferenceResolverService.findLatestEnabledByCode(...)`
- `BusinessQueryActionService.findLatestEnabledByCode(...)`
- `PreconditionCheckService.findLatestEnabledByCode(...)`
- `ActionSpecService.findLatestEnabledByCode(...)`
- `InteractionSpecService.findLatestEnabledByCode(...)`

**Step 3: Re-run the Task 1 tests**

Run: `mvn -pl assistant-controlplane "-Dtest=ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest" test`
Expected: PASS.

**Step 4: Commit**

```bash
git add assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add phase2 definition entities and mappers"
```

### Task 4: Verify Phase 2 remains additive and compatible

**Files:**
- Verify only; no required new files.

**Step 1: Run focused control-plane tests**

Run: `mvn -pl assistant-controlplane "-Dtest=ToolMetaServiceContractTest,CapabilityToToolMetaMigratorTest,PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest,ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest" test`
Expected: PASS.

**Step 2: Run compile for dependent modules**

Run: `mvn -pl assistant-controlplane,assistant-runtime,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 3: Review compatibility boundaries**

Confirm that no runtime path has been switched away from `tool_meta` or legacy registry tables yet. Phase 2 must still only add storage and services.

**Step 4: Commit**

```bash
git add .
git commit -m "test: verify phase2 enterprise control plane baseline"
```
