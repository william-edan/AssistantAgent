# Enterprise Control Plane Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Introduce the first enterprise control-plane foundation for private deployment by adding `platform_space`, `connector`, `auth_profile`, and `principal_binding_v2` storage plus minimal Java services without breaking the legacy migration flow.

**Architecture:** Phase 1 is additive. New Flyway migrations create the first control-plane tables, and `assistant-controlplane` gains entity/mapper/service layers that expose minimal query contracts for the new models. No existing runtime path is removed; the new services sit alongside `tool_meta`, `identity_binding`, and `system_access_profile` so later compiler work can bridge old and new models.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Flyway, JUnit 5, Mockito, Maven.

---

### Task 1: Add Phase 1 contract tests for new control-plane services

**Files:**
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/space/PlatformSpaceServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/connector/ConnectorServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/connector/AuthProfileServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/identity/PrincipalBindingV2ServiceContractTest.java`

**Step 1: Write the failing tests**

Write contract tests that define these minimal behaviors:
- `PlatformSpaceService.normalizeEnvironment(null)` returns `prod`.
- `PlatformSpaceService.findActiveByCode(spaceCode, environment)` delegates to `getOne(...)` and ignores blank `spaceCode`.
- `ConnectorService.findLatestActiveByCode(spaceId, connectorCode)` delegates to `getOne(...)` with active-status filtering.
- `AuthProfileService.listActiveByConnector(connectorId)` delegates to `list(...)`.
- `PrincipalBindingV2Service.findHighestPriorityActiveBinding(spaceId, connectorId, platformPrincipalId)` delegates to `getOne(...)` and ignores blank principal ids.

**Step 2: Run tests to verify they fail**

Run: `mvn -pl assistant-controlplane -Dtest=PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest test`
Expected: build fails because the new production classes do not exist yet.

**Step 3: Write minimal implementation**

Add the new entities, mappers, and services with only the fields and query helpers needed by the contract tests. Reuse the existing `ToolMetaService` style: `ServiceImpl<Mapper, Entity>` plus package-private helper methods for query construction and normalization.

**Step 4: Run tests to verify they pass**

Run: `mvn -pl assistant-controlplane -Dtest=PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add phase1 control plane services"
```

### Task 2: Add Flyway migrations for Phase 1 tables

**Files:**
- Create: `assistant-infra/src/main/resources/db/migration/V13__create_platform_space.sql`
- Create: `assistant-infra/src/main/resources/db/migration/V14__create_connector_and_auth_profile.sql`
- Create: `assistant-infra/src/main/resources/db/migration/V15__create_principal_binding_v2.sql`

**Step 1: Write a migration smoke test target**

Use compile-time verification plus SQL review. The immediate test target is that the new files are discovered by Flyway and compile cleanly with the rest of the project.

**Step 2: Add migration SQL**

Create the three SQL files exactly as defined in `docs/plans/2026-03-10-enterprise-physical-ddl-java-model-and-migration-plan.md`, keeping naming, indexes, and comments aligned with the design baseline.

**Step 3: Run module compile to verify the new resources are accepted**

Run: `mvn -pl assistant-infra -DskipTests compile`
Expected: PASS.

**Step 4: Commit**

```bash
git add assistant-infra/src/main/resources/db/migration
git commit -m "feat(infra): add phase1 enterprise control plane migrations"
```

### Task 3: Add minimal Java entity and mapper skeletons for Phase 1 models

**Files:**
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/space/PlatformSpace.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/space/PlatformSpaceService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/space/mapper/PlatformSpaceMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/connector/Connector.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/connector/ConnectorService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/connector/AuthProfile.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/connector/AuthProfileService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/connector/mapper/ConnectorMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/connector/mapper/AuthProfileMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/identity/PrincipalBindingV2.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/identity/PrincipalBindingV2Service.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/identity/mapper/PrincipalBindingV2Mapper.java`

**Step 1: Implement only the fields used by Phase 1 queries**

Each entity should include MyBatis-Plus annotations, audit timestamps, and the key columns needed by Phase 1. Avoid adding workflow or compiler concerns in this batch.

**Step 2: Implement minimal service contracts**

Expose only these query helpers:
- `PlatformSpaceService.findActiveByCode(...)`
- `ConnectorService.findLatestActiveByCode(...)`
- `AuthProfileService.listActiveByConnector(...)`
- `PrincipalBindingV2Service.findHighestPriorityActiveBinding(...)`

**Step 3: Re-run the Task 1 tests**

Run: `mvn -pl assistant-controlplane -Dtest=PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest test`
Expected: PASS.

**Step 4: Commit**

```bash
git add assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add enterprise phase1 entities and mappers"
```

### Task 4: Verify Phase 1 integrates cleanly with the existing build

**Files:**
- Verify only; no required new files.

**Step 1: Run focused control-plane tests**

Run: `mvn -pl assistant-controlplane -Dtest=ToolMetaServiceContractTest,CapabilityToToolMetaMigratorTest,PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest test`
Expected: PASS.

**Step 2: Run compile for the main dependent modules**

Run: `mvn -pl assistant-controlplane,assistant-runtime,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 3: Review compatibility boundaries**

Confirm that no existing class stops using `tool_meta`, `identity_binding`, or `system_access_profile`. Phase 1 must be additive only.

**Step 4: Commit**

```bash
git add .
git commit -m "test: verify phase1 enterprise control plane baseline"
```
