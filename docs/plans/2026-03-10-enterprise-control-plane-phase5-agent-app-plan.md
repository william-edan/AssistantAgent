# Enterprise Control Plane Phase 5 Agent App Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add the `agent_app` and `agent_app_grant` control-plane layer so the platform can model which app is exposed to users and which workflows, actions, or connectors that app is allowed to invoke.

**Architecture:** Phase 5 stays additive and control-plane only. New Flyway migration creates the `agent_app` and `agent_app_grant` tables, and `assistant-controlplane` gains entities, mappers, and services for app lookup and grant listing. The current runtime still does not enforce app grants yet; this phase only creates the persistence and service contracts needed for later authorization decisions.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Flyway, JUnit 5, Mockito, Maven.

---

### Task 1: Add agent app contract tests

**Files:**
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/agentapp/AgentAppServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/agentapp/AgentAppGrantServiceContractTest.java`

**Step 1: Write the failing tests**

Write contract tests for these minimal behaviors:
- `AgentAppService.findActiveByCode(spaceId, agentAppCode)` ignores blank codes and delegates to `getOne(...)`.
- `AgentAppGrantService.listByAgentAppId(agentAppId)` returns empty for null ids and delegates to `list(...)` for valid ids.

**Step 2: Run tests to verify they fail**

Run: `mvn -pl assistant-controlplane "-Dtest=AgentAppServiceContractTest,AgentAppGrantServiceContractTest" test`
Expected: build fails because the new production classes do not exist yet.

**Step 3: Write minimal implementation**

Add the new entities, mappers, and services with only the fields and query helpers needed by the contract tests.

**Step 4: Run tests to verify they pass**

Run: `mvn -pl assistant-controlplane "-Dtest=AgentAppServiceContractTest,AgentAppGrantServiceContractTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add phase5 agent app services"
```

### Task 2: Add Flyway migration for agent app tables

**Files:**
- Create: `assistant-infra/src/main/resources/db/migration/V19__create_agent_app_tables.sql`

**Step 1: Add migration SQL**

Create the SQL file exactly as defined in `docs/plans/2026-03-10-enterprise-physical-ddl-java-model-and-migration-plan.md`, including `agent_app` and `agent_app_grant` plus indexes.

**Step 2: Run module compile to verify the resource is accepted**

Run: `mvn -pl assistant-infra -DskipTests compile`
Expected: PASS.

**Step 3: Commit**

```bash
git add assistant-infra/src/main/resources/db/migration/V19__create_agent_app_tables.sql
git commit -m "feat(infra): add phase5 agent app migrations"
```

### Task 3: Add minimal Java entity and mapper skeletons for agent app models

**Files:**
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp/AgentApp.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp/AgentAppService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp/AgentAppGrant.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp/AgentAppGrantService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp/mapper/AgentAppMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp/mapper/AgentAppGrantMapper.java`

**Step 1: Implement only the fields used by the Phase 5 baseline**

Each entity should include MyBatis-Plus annotations, timestamps, and the JSON policy/config columns defined in the physical DDL draft.

**Step 2: Implement minimal service contracts**

Expose only these query helpers:
- `AgentAppService.findActiveByCode(...)`
- `AgentAppGrantService.listByAgentAppId(...)`

**Step 3: Re-run the Task 1 tests**

Run: `mvn -pl assistant-controlplane "-Dtest=AgentAppServiceContractTest,AgentAppGrantServiceContractTest" test`
Expected: PASS.

**Step 4: Commit**

```bash
git add assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/agentapp
git commit -m "feat(controlplane): add phase5 agent app entities and mappers"
```

### Task 4: Verify Phase 5 remains additive and compatible

**Files:**
- Verify only; no required new files.

**Step 1: Run focused control-plane tests**

Run: `mvn -pl assistant-controlplane "-Dtest=ToolMetaServiceContractTest,CapabilityToToolMetaMigratorTest,PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest,ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest,WorkflowSpecServiceContractTest,WorkflowStepServiceContractTest,LegacyCapabilityCompilerTest,AgentAppServiceContractTest,AgentAppGrantServiceContractTest" test`
Expected: PASS.

**Step 2: Run compile for dependent modules**

Run: `mvn -pl assistant-controlplane,assistant-runtime,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 3: Review compatibility boundaries**

Confirm that runtime still does not enforce `agent_app_grant` yet. This phase only adds app and grant storage plus lookup services.

**Step 4: Commit**

```bash
git add .
git commit -m "test: verify phase5 agent app baseline"
```
