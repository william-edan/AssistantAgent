# Enterprise Control Plane Phase 3 Workflow Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add the enterprise workflow control-plane baseline by introducing `workflow_spec` and `workflow_step` storage plus minimal Java services that can load enabled workflow definitions and ordered steps.

**Architecture:** Phase 3 is still additive. New Flyway migration creates workflow definition tables, and `assistant-controlplane` gains workflow entities, mappers, and services. The current execution engine is not switched to the new workflow model yet; this batch only establishes the control-plane storage and lookup contracts needed for the later compiler and runtime bridge.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Flyway, JUnit 5, Mockito, Maven.

---

### Task 1: Add workflow contract tests

**Files:**
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/workflow/WorkflowSpecServiceContractTest.java`
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/workflow/WorkflowStepServiceContractTest.java`

**Step 1: Write the failing tests**

Write contract tests for these minimal behaviors:
- `WorkflowSpecService.findLatestEnabledByCode(spaceId, workflowCode)` ignores blank codes and delegates to `getOne(...)`.
- `WorkflowStepService.listEnabledByWorkflowId(workflowId)` returns empty for null ids and delegates to `list(...)` for valid workflow ids.

**Step 2: Run tests to verify they fail**

Run: `mvn -pl assistant-controlplane "-Dtest=WorkflowSpecServiceContractTest,WorkflowStepServiceContractTest" test`
Expected: build fails because the new production classes do not exist yet.

**Step 3: Write minimal implementation**

Add the new workflow entities, mappers, and services with only the fields and query helpers needed by the contract tests.

**Step 4: Run tests to verify they pass**

Run: `mvn -pl assistant-controlplane "-Dtest=WorkflowSpecServiceContractTest,WorkflowStepServiceContractTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add phase3 workflow services"
```

### Task 2: Add Flyway migration for workflow tables

**Files:**
- Create: `assistant-infra/src/main/resources/db/migration/V18__create_workflow_tables.sql`

**Step 1: Add migration SQL**

Create the SQL file exactly as defined in `docs/plans/2026-03-10-enterprise-physical-ddl-java-model-and-migration-plan.md`, including `workflow_spec` and `workflow_step` plus indexes.

**Step 2: Run module compile to verify the resource is accepted**

Run: `mvn -pl assistant-infra -DskipTests compile`
Expected: PASS.

**Step 3: Commit**

```bash
git add assistant-infra/src/main/resources/db/migration/V18__create_workflow_tables.sql
git commit -m "feat(infra): add phase3 workflow migrations"
```

### Task 3: Add minimal Java entity and mapper skeletons for workflow models

**Files:**
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow/WorkflowSpec.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow/WorkflowSpecService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow/WorkflowStep.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow/WorkflowStepService.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow/mapper/WorkflowSpecMapper.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow/mapper/WorkflowStepMapper.java`

**Step 1: Implement only the fields used by the Phase 3 baseline**

Each entity should include MyBatis-Plus annotations, audit timestamps, version, status, and the JSON policy/config columns defined in the physical DDL draft.

**Step 2: Implement minimal service contracts**

Expose only these query helpers:
- `WorkflowSpecService.findLatestEnabledByCode(...)`
- `WorkflowStepService.listEnabledByWorkflowId(...)`

**Step 3: Re-run the Task 1 tests**

Run: `mvn -pl assistant-controlplane "-Dtest=WorkflowSpecServiceContractTest,WorkflowStepServiceContractTest" test`
Expected: PASS.

**Step 4: Commit**

```bash
git add assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/workflow
git commit -m "feat(controlplane): add phase3 workflow entities and mappers"
```

### Task 4: Verify Phase 3 remains additive and compatible

**Files:**
- Verify only; no required new files.

**Step 1: Run focused control-plane tests**

Run: `mvn -pl assistant-controlplane "-Dtest=ToolMetaServiceContractTest,CapabilityToToolMetaMigratorTest,PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest,ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest,WorkflowSpecServiceContractTest,WorkflowStepServiceContractTest" test`
Expected: PASS.

**Step 2: Run compile for dependent modules**

Run: `mvn -pl assistant-controlplane,assistant-runtime,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 3: Review compatibility boundaries**

Confirm that runtime still reads legacy capability and flow definitions. This phase must not route execution to the new workflow tables yet.

**Step 4: Commit**

```bash
git add .
git commit -m "test: verify phase3 workflow baseline"
```
