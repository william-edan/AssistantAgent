# Wave 6 Approval Runtime Semantics Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Promote approval wait/resume and workflow dependency semantics into the artifact-native runtime without falling back to legacy capability or human-interruption paths.

**Architecture:** This wave keeps the best-route spine intact: `RuntimeArtifact -> ArtifactRuntimeExecutor -> DAGFlowExecutor -> execution persistence / SSE / control-plane APIs`. The work is split into three low-overlap lanes: runtime scheduler semantics, approval persistence and resume wiring, and operator-facing approval/execution APIs. The orchestrator owns the shared status and result contract before dispatch.

**Tech Stack:** Java 17, Spring Boot 3, Reactor, MyBatis-Plus, Maven, JUnit 5, Mockito, Flyway.

---

## Task 0: Orchestrator Contract Lock

**Files:**
- Modify: `docs/plans/2026-03-10-runtime-execution-contract-plan.md`
- Create: `docs/plans/2026-03-10-wave6-approval-runtime-semantics-plan.md`

**Step 1: Freeze the pause/skip semantics**

Document that:
1. `FlowExecutionResult` may represent `WAITING_APPROVAL`
2. `SKIPPED` is a lifecycle status, not a new event type
3. `ApprovalRequest` is the durable resume anchor

**Step 2: Verify the new wave boundaries**

Confirm the worker lanes do not simultaneously modify:
- `assistant-api/src/main/java/com/alibaba/assistant/agent/api/controller/ChatController.java`
- `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/flow/DAGFlowExecutor.java`
- `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/execution/ArtifactRuntimeExecutor.java`

**Step 3: Commit the planning baseline**

```bash
git add docs/plans/2026-03-10-runtime-execution-contract-plan.md docs/plans/2026-03-10-wave6-approval-runtime-semantics-plan.md
git commit -m "docs(runtime): plan wave6 approval semantics"
```

---

## Task 1: Worker A - Workflow Dependency and Pause Semantics

**Files:**
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/model/StepStatus.java`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/flow/FlowExecutionResult.java`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/flow/FlowExecutionListener.java`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/flow/FlowContext.java`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/flow/DAGFlowExecutor.java`
- Test: `assistant-execution/src/test/java/com/alibaba/assistant/agent/execution/flow/DAGFlowExecutorTest.java`

**Step 1: Write the failing tests**

Cover:
1. dependency-driven order respects `dependsOn`
2. `JoinType.ANY` step becomes ready when any dependency completes
3. false `condition` causes step lifecycle `SKIPPED`
4. approval-gated step returns `WAITING_APPROVAL` before HTTP execution

**Step 2: Run the focused tests to verify RED**

```bash
mvn -pl assistant-execution "-Dtest=DAGFlowExecutorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: fail on missing scheduling/pause behavior.

**Step 3: Implement the minimal scheduler changes**

Add:
1. expanded `StepStatus`
2. explicit flow lifecycle status / pause metadata in `FlowExecutionResult`
3. ready-step traversal based on `dependsOn` and `joinType`
4. condition evaluation for simple boolean / `{enabled:true, expression:true}` payloads
5. approval wait hook before side-effect execution

**Step 4: Re-run focused tests to verify GREEN**

Use the same Maven command and confirm PASS.

**Step 5: Commit**

```bash
git add assistant-execution/src/main/java assistant-execution/src/test/java
git commit -m "feat(execution): add approval-aware workflow scheduling"
```

---

## Task 2: Worker B - Approval Persistence and Runtime Resume Wiring

**Files:**
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/execution/ArtifactRuntimeExecutor.java`
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/execution/ExecutionRuntimePersistenceRecorder.java`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/persistence/ApprovalRequestService.java`
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/persistence/ExecutionRunService.java`
- Create or modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/execution/ArtifactRuntimeResumeService.java`
- Test: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/execution/ArtifactRuntimeExecutorApprovalTest.java`
- Test: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/execution/ArtifactRuntimeResumeServiceTest.java`

**Step 1: Write the failing tests**

Cover:
1. waiting approval emits `STEP_WAITING_APPROVAL`
2. waiting approval persists `execution_run`, `execution_step`, and `approval_request`
3. approving a persisted request lets the runtime resume and emit `RUN_RESUMED`

**Step 2: Run tests to verify RED**

```bash
mvn -pl assistant-runtime,assistant-execution -am "-Dtest=ArtifactRuntimeExecutorApprovalTest,ArtifactRuntimeResumeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: fail because pause/resume persistence is missing.

**Step 3: Implement minimal runtime wiring**

Add:
1. persistence of approval requests when flow pauses
2. ability to look up a paused run and approval request by `runId` / `requestId`
3. resume execution that re-enters `ArtifactRuntimeExecutor` with approval feedback and preserved arguments/context

**Step 4: Re-run tests to verify GREEN**

Use the same Maven command and confirm PASS.

**Step 5: Commit**

```bash
git add assistant-runtime/src/main/java assistant-runtime/src/test/java assistant-execution/src/main/java
git commit -m "feat(runtime): persist and resume approval-gated executions"
```

---

## Task 3: Worker C - Operator Approval and Execution Queue APIs

**Files:**
- Modify: `assistant-execution/src/main/java/com/alibaba/assistant/agent/execution/persistence/ExecutionHistoryService.java`
- Create: `assistant-api/src/main/java/com/alibaba/assistant/agent/api/controller/ExecutionApprovalController.java`
- Create DTOs under: `assistant-api/src/main/java/com/alibaba/assistant/agent/api/controller/dto/`
- Test: `assistant-api/src/test/java/com/alibaba/assistant/agent/api/controller/ExecutionApprovalControllerTest.java`
- Test: `assistant-api/src/test/java/com/alibaba/assistant/agent/api/security/ExecutionApprovalControllerSecurityTest.java`

**Step 1: Write the failing tests**

Cover:
1. list pending approval requests for a space
2. approve or reject a request through typed DTOs
3. scope-protected access returns `403` when caller lacks control-plane rights

**Step 2: Run tests to verify RED**

```bash
mvn -pl assistant-api,assistant-execution -am "-Dtest=ExecutionApprovalControllerTest,ExecutionApprovalControllerSecurityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: fail because the controller and query surface do not exist yet.

**Step 3: Implement the minimal API surface**

Add:
1. `GET /api/controlplane/approval-requests`
2. `POST /api/controlplane/approval-requests/{requestId}/approve`
3. `POST /api/controlplane/approval-requests/{requestId}/reject`
4. typed response DTOs backed by existing migration control-plane authorization rules

**Step 4: Re-run tests to verify GREEN**

Use the same Maven command and confirm PASS.

**Step 5: Commit**

```bash
git add assistant-api/src/main/java assistant-api/src/test/java assistant-execution/src/main/java
git commit -m "feat(api): add approval queue control plane endpoints"
```

---

## Task 4: Orchestrator Integration

**Files:**
- Modify only if needed: `assistant-api/src/main/java/com/alibaba/assistant/agent/api/controller/ChatController.java`
- Modify only if needed: `assistant-api/src/main/java/com/alibaba/assistant/agent/api/controller/ExecutionHistoryController.java`
- Modify docs if behavior changed

**Step 1: Cherry-pick or merge the worker commits into the main worktree**

Resolve conflicts only in orchestrator-owned files.

**Step 2: Run focused cross-module verification**

```bash
mvn -pl assistant-runtime,assistant-execution,assistant-api -am "-Dtest=*Execution*,*Approval*,*Artifact*" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS for the new runtime and approval coverage.

**Step 3: Run a full project regression**

```bash
mvn test -DskipITs
```

Expected: `BUILD SUCCESS`.

**Step 4: Commit the integrated wave**

```bash
git add assistant-runtime assistant-execution assistant-api docs/plans
git commit -m "feat(runtime): add approval-aware artifact execution flow"
```
