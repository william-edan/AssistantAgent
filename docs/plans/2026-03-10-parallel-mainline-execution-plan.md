# Parallel Mainline Execution Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Finish the remaining best-route mainline work by dispatching multiple agents in parallel without letting them collide on the same runtime spine.

**Architecture:** The remaining work should not be executed as one broad migration. It should be split into wave-based parallel batches around stable boundaries: control-plane management surface, execution persistence, auth resolution backbone, workflow runtime replacement, event/SSE contract, and legacy shrink. Each wave locks shared contracts first, then dispatches agents only into domains with low file overlap.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Maven, JUnit 5, Mockito, Flyway.

---

## 1. Planning Assumptions

1. Best-route mainline remains mandatory: `RuntimeArtifact -> publication backbone -> provider-driven registry -> artifact-native workflow runtime`.
2. Legacy `ToolMeta / CapabilityBridge / systemCode` code is compatibility-only and must not receive new primary semantics.
3. The current workspace already contains the new control-plane foundation, publication backbone, source selection, and migration-boundary admin APIs.
4. The remaining work is now dominated by runtime replacement and productization, not by schema bootstrapping.

---

## 2. Remaining Workstreams

The remaining work should be treated as six workstreams:

1. `Execution Runtime Backbone`
   `execution_run / execution_step / approval_request / audit_event` tables, mappers, services, persistence contracts.
2. `Credential Broker & Auth Resolver`
   connector/authProfile/principalBinding-driven runtime credential resolution.
3. `Artifact-Native Workflow Runtime`
   real workflow executor, step orchestration, retry/resume/approval hooks, runtime artifact execution contract.
4. `Execution Event Contract & SSE Convergence`
   step-level events, controller streaming, approval wait/resume signals, audit emission.
5. `Control-Plane Product Surface`
   CRUD/query APIs for connectors, auth profiles, actions, workflows, agent apps, local users, and policy views.
6. `Legacy Shrink & Cutover`
   feature flags, artifact-first default routing, compatibility-provider reduction, regression coverage.

These are not equally parallelizable. Workstreams 3 and 4 depend on shared runtime contracts. Workstream 6 depends on all previous ones.

---

## 3. Recommended Parallel Strategy

Use `1 orchestrator + 3 worker agents` as the default concurrency limit.

Why not more:

1. `assistant-runtime` and `assistant-execution` now share too many central files.
2. More than three active workers sharply increases conflict probability around runtime contracts.
3. The gain from a fourth or fifth concurrent agent is smaller than the merge/review cost.

Roles:

1. `Orchestrator`
   owns contract freezing, reviews worker outputs, rebases mental model, runs integration verification.
2. `Worker A`
   owns control-plane or API-facing independent tasks.
3. `Worker B`
   owns runtime/auth tasks.
4. `Worker C`
   owns execution/runtime persistence tasks.

---

## 4. Wave 0: Contract Freeze (Sequential, short)

Do not parallelize this wave.

### Task 0.1: Lock shared runtime contracts

**Files:**
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/execution/*`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/*`
- Modify: `docs/plans/2026-03-10-enterprise-control-plane-schema-and-runtime-design.md`
- Create or modify: `docs/plans/2026-03-10-runtime-execution-contract-plan.md`

**Outcome:**
- freeze `ExecutionEvent` types
- freeze execution persistence aggregate names
- freeze credential resolution input/output contract
- freeze workflow runtime step contract
- define which legacy compatibility fields are still allowed in artifact execution descriptors

**Reason:**
Without this wave, Agents B/C/D will edit the same runtime seam differently.

**Verification:**
- no implementation yet; only doc + interface skeletons reviewed by orchestrator

---

## 5. Wave 1: Safest Parallel Batch

These tasks are independent once Wave 0 contracts are frozen.

### Agent A: Control-Plane Product Surface

**Scope:**
- `assistant-controlplane`
- `assistant-api/controller`
- DTOs and service facades only

**Owns:**
1. connector/auth-profile management APIs
2. action/workflow/interaction query/list/get endpoints
3. local-user and agent-app management typed endpoints
4. admin-side validation and response normalization

**Must not touch:**
- `assistant-runtime`
- `assistant-execution`
- `SecurityConfig` except when orchestrator explicitly assigns a coarse route addition

**Deliverables:**
- typed control-plane APIs
- controller/service tests
- zero raw grant/tool_meta leakage upward

### Agent B: Execution Runtime Persistence

**Scope:**
- `assistant-infra`
- `assistant-execution`
- execution persistence model only

**Owns:**
1. Flyway migrations for `execution_run / execution_step / approval_request / audit_event`
2. mappers/entities/repositories
3. persistence service contracts
4. repository and mapper tests

**Must not touch:**
- controller streaming
- runtime tool registry
- credential broker logic

**Deliverables:**
- persistence layer exists before runtime integration
- tests prove create/update/query behavior

### Agent C: Credential Broker Backbone

**Scope:**
- `assistant-runtime`
- minimal `assistant-controlplane` reads if required

**Owns:**
1. `CredentialBroker` / `CredentialLease` / auth resolution interfaces
2. connector/authProfile/principalBinding-driven resolution service
3. cache/lease policy abstractions
4. focused unit tests

**Must not touch:**
- workflow runtime execution loop
- controller streaming
- legacy bridge core logic beyond adapter boundaries

**Deliverables:**
- runtime can resolve credential plans without yet changing the executor

### Wave 1 Integration Checkpoint

Run after all three workers return:

```bash
mvn -pl assistant-controlplane,assistant-runtime,assistant-execution,assistant-api -am test -DskipITs
mvn -pl assistant-controlplane,assistant-runtime,assistant-execution,assistant-api -am -DskipTests compile
```

Expected result:
- new APIs compile
- persistence layer compiles
- credential resolution backbone compiles
- no shared contract drift

---

## 6. Wave 2: Runtime Replacement Batch

Only start after Wave 1 is integrated.

### Agent A: Artifact-Native Workflow Runtime

**Scope:**
- `assistant-execution`
- `assistant-runtime/execution`

**Owns:**
1. true workflow execution path for artifact-backed actions/workflows
2. step dependency traversal, retry hooks, timeout hooks, resume points
3. approval wait state contract
4. runtime executor tests

**Must not touch:**
- control-plane CRUD APIs
- SSE controller layer
- security model

### Agent B: Execution Event Contract & SSE

**Scope:**
- `assistant-api`
- `assistant-runtime`

**Owns:**
1. step-level `started / waiting_approval / completed / failed` events
2. SSE bridge from execution runtime to controller stream
3. event payload DTOs and streaming tests
4. resume signal/controller contract

**Must not touch:**
- workflow scheduling internals except agreed event interface
- control-plane CRUD surface

### Agent C: Runtime Wiring to Credential Broker

**Scope:**
- `assistant-runtime`
- `assistant-execution`

**Owns:**
1. executor integration with `CredentialBroker`
2. step-level connector/authProfile/binding resolution
3. compatibility shims for any still-required legacy identity fields
4. auth-resolution integration tests

**Must not touch:**
- publication provider selection logic unless required by orchestrator
- controller SSE payload design

### Wave 2 Integration Checkpoint

Run after all three workers return:

```bash
mvn -pl assistant-runtime,assistant-execution,assistant-api -am "-Dtest=*Execution*,*Artifact*,*Sse*,*Credential*" test
mvn -pl assistant-controlplane,assistant-runtime,assistant-execution,assistant-api -am -DskipTests compile
```

Expected result:
- artifact-native runtime is executable
- step-level events stream correctly
- credential resolution is no longer only a dormant backbone

---

## 7. Wave 3: Cutover and Shrink Batch

Only start after Wave 2 is green.

### Agent A: Artifact-First Default Routing

**Scope:**
- `assistant-runtime`
- `assistant-agent-core`

**Owns:**
1. make artifact publication the default runtime path
2. reduce legacy-provider default visibility
3. add explicit flags for fallback-to-legacy behavior

### Agent B: Legacy Shrink and Regression Safety

**Scope:**
- `assistant-runtime`
- `assistant-api`
- tests/docs only where needed

**Owns:**
1. regression coverage comparing artifact path and legacy fallback
2. compatibility matrix documentation
3. deprecation warnings and observability hooks

### Agent C: Control-Plane Operational Finishers

**Scope:**
- `assistant-api`
- `assistant-controlplane`

**Owns:**
1. list/search endpoints needed by admin UI to manage new models
2. policy inspection endpoints
3. operator-facing error normalization

### Wave 3 Integration Checkpoint

```bash
mvn test -DskipITs
mvn -pl assistant-agent-start -am -DskipTests compile
```

Expected result:
- artifact-first path is default-safe
- legacy path is compatibility-only
- operator APIs are sufficient for management workflows

---

## 8. File-Collision Rules

Do not dispatch workers in parallel if they would both need any of these files in the same wave:

1. `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/agent/AssistantAgentFactory.java`
2. `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/TenantAwareToolRegistry.java`
3. `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/tool/react/SlotCollectTool.java`
4. `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/tool/react/SlotConfirmTool.java`
5. `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/prompt/ToolCatalogContributor.java`
6. `assistant-api/src/main/java/com/alibaba/assistant/agent/api/security/SecurityConfig.java`
7. `assistant-api/src/main/java/com/alibaba/assistant/agent/api/controller/ChatController.java`
8. `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/flow/DAGFlowExecutor.java`

If two workers need one of these files, they must be split into different waves or one worker becomes the owner for that file.

---

## 9. Review Protocol for Each Worker

Each worker must return:

1. scope actually touched
2. files changed
3. tests added
4. tests run
5. unresolved risks
6. whether any shared-contract assumption changed

The orchestrator must then:

1. review summary first
2. inspect shared-file diffs
3. run wave-level verification
4. only then merge mentally into the next wave

---

## 10. Recommended First Dispatch

If execution starts immediately, the first parallel dispatch should be:

1. `Agent A`: control-plane product surface
2. `Agent B`: execution runtime persistence
3. `Agent C`: credential broker backbone

This is the highest-yield, lowest-conflict batch.

Do not start with workflow runtime + SSE + cutover at the same time. That creates avoidable conflicts in `assistant-runtime` and `assistant-api`.

---

## 11. Success Criteria

The parallel plan is successful only if all of the following become true:

1. new work lands on the best-route mainline rather than expanding legacy bridge logic
2. no wave requires large manual conflict repair across the same central runtime files
3. artifact-native runtime replaces legacy execution for the default path
4. control-plane administration no longer depends on raw grant rows or legacy-only semantics
5. legacy bridge remains only as compatibility fallback

---

## 12. Execution Choice

Plan complete and saved to `docs/plans/2026-03-10-parallel-mainline-execution-plan.md`.

Recommended execution mode:

1. `Subagent-Driven (this session)`
   orchestrator stays here, dispatches fresh workers per wave, reviews after each wave
2. `Parallel Session (separate)`
   open a new execution session dedicated to this plan

