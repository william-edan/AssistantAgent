# Runtime Execution Contract Plan

> Date: 2026-03-10
> Scope: `D:/devfive/AssistantAgent`
> Purpose: Freeze the shared runtime contracts that must stay stable before Wave 1 and Wave 2 parallel implementation starts.
> Related:
> - `docs/plans/2026-03-10-parallel-mainline-execution-plan.md`
> - `docs/plans/2026-03-10-enterprise-control-plane-schema-and-runtime-design.md`
> - `docs/plans/2026-03-10-runtime-artifact-publication-backbone-plan.md`

---

## 1. Why This Freeze Exists

The current artifact path already has:

1. `RuntimeArtifact`
2. artifact publication providers
3. `ArtifactRuntimeExecutor`
4. `DAGFlowExecutor`

But the execution seam is still underspecified in four places:

1. no stable step-level event contract
2. no stable credential resolution contract
3. no stable execution persistence aggregate naming
4. no stable rule for how compatibility fields such as `systemCode` may survive during cutover

If Wave 1 and Wave 2 start without freezing these seams first, different workers will invent slightly different contracts in `assistant-runtime`, `assistant-execution`, and `assistant-api`.

---

## 2. Current Baseline Observations

### 2.1 Runtime entry

`ArtifactRuntimeExecutor` currently delegates directly to `DAGFlowExecutor` and then returns a final payload map.

Implication:

1. execution is still result-oriented, not event-oriented
2. step persistence and approval wait states do not yet exist as first-class runtime concepts
3. compatibility context is still pushed through `FlowContext` using `systemCode`, `assistantUid`, and `threadId`

### 2.2 Executor behavior

`DAGFlowExecutor` still has linear execution semantics:

1. execution order is built from `entry -> next`
2. unsupported step types fail immediately
3. there is no step-level persistence boundary
4. there is no event publication contract
5. there is no credential-resolution abstraction at the step boundary

### 2.3 Compiled artifact contract

`RuntimeArtifact.StepBinding` is already the richest compiled step descriptor currently present in the codebase.

Decision:

Do not invent a second competing step-definition model during Wave 1 or Wave 2. The workflow runtime should treat `RuntimeArtifact.StepBinding` as the canonical compiled step contract and layer execution state around it.

---

## 3. Frozen Shared Contracts

## 3.1 Execution aggregate names

The following names are now frozen for the remaining mainline work:

1. `ExecutionRun`
2. `ExecutionStep`
3. `ApprovalRequest`
4. `AuditEvent`
5. `CredentialLease`

Rules:

1. persistence tables, Java entities, service names, and event payloads should use these names consistently
2. do not introduce parallel names such as `FlowRun`, `TaskRun`, `NodeExecution`, or `TokenSession` for the same concepts

## 3.2 Lifecycle status contract

A single lifecycle vocabulary will be used across eventing, persistence, and API streaming:

1. `PENDING`
2. `RUNNING`
3. `WAITING_APPROVAL`
4. `COMPLETED`
5. `FAILED`
6. `SKIPPED`
7. `CANCELLED`

Rules:

1. existing legacy `StepStatus` may continue to exist for compatibility, but new runtime work should converge on the frozen lifecycle vocabulary above
2. Wave 2 must map any legacy-only status into this vocabulary before emitting step-level events

## 3.3 Execution event types

The event stream will use the following stable event types:

1. `RUN_STARTED`
2. `STEP_STARTED`
3. `STEP_WAITING_APPROVAL`
4. `STEP_COMPLETED`
5. `STEP_FAILED`
6. `RUN_COMPLETED`
7. `RUN_FAILED`
8. `RUN_RESUMED`

Rules:

1. SSE payloads must be derived from these events rather than inventing controller-only event names
2. audit and persistence writers may consume the same event contract
3. a single `ExecutionEvent` payload shape should be reusable across runtime, API, and tests

## 3.4 Credential resolution contract

The shared credential resolution seam is frozen as:

1. `CredentialResolutionRequest`
2. `ResolvedCredentialLease`
3. `CredentialBroker`

`CredentialResolutionRequest` must describe:

1. `spaceId`
2. `connectorId`
3. `candidateAuthProfileCodes`
4. `platformPrincipalId`
5. `platformPrincipalType`
6. `requestedScopes`
7. `runId`
8. `stepId`
9. optional compatibility hints

`ResolvedCredentialLease` must describe:

1. `leaseKey`
2. `authProfileCode`
3. `principalBindingId`
4. `connectorId`
5. `credentialType`
6. `headers`
7. `expiresAt`
8. optional compatibility `systemCode`

Rules:

1. Wave 1 may implement these as interfaces and immutable records first
2. Wave 2 executor wiring must depend on this seam instead of directly reading token-exchange details
3. compatibility `systemCode` is allowed only as a compatibility field on the resolved lease, not as the primary lookup key

## 3.5 Workflow step execution contract

The canonical compiled step contract remains:

1. `RuntimeArtifact.StepBinding`

The runtime execution layer may add:

1. execution state
2. persistence identifiers
3. resolved credential lease reference
4. timestamps
5. approval wait metadata

The runtime execution layer must not add:

1. a second primary source-of-truth step-definition type that duplicates StepBinding
2. a new publication-time conversion back into ToolMeta

## 3.6 Flow result and pause/resume contract

FlowExecutionResult remains the executor return envelope, but it must represent non-terminal pause states during cutover.

Required semantics:

1. success=true means the run reached a successful terminal state
2. success=false does not automatically mean failure; it may also mean WAITING_APPROVAL
3. the result object must carry the run lifecycle status explicitly
4. when the lifecycle status is WAITING_APPROVAL, the result must carry enough metadata to identify the paused step and the persisted approval request

## 3.7 Step skip semantics

The frozen event contract intentionally does not add a separate STEP_SKIPPED event type.

Rules:

1. dependency- or condition-driven skips should use lifecycle status SKIPPED
2. controller payloads and persistence may surface SKIPPED
3. if a step-level event must be emitted for a skipped step during cutover, it should reuse STEP_COMPLETED with lifecycle status SKIPPED

## 3.8 Approval wait contract

Approval gating during artifact-native execution is frozen as:

1. a workflow step may enter WAITING_APPROVAL before its side-effecting execution starts
2. a persisted ApprovalRequest row becomes the durable handshake between runtime pause and operator action
3. the runtime event stream must emit STEP_WAITING_APPROVAL
4. resumption must emit RUN_RESUMED

---

## 4. Compatibility Boundary Rules

The following fields remain compatibility-only:

1. `systemCode`
2. `assistantUid`
3. `threadId`

Rules:

1. these fields may still travel through compatibility context for legacy interoperability
2. they must not become the primary identity of execution persistence, credential resolution, or step routing
3. any new runtime API should prefer `runId`, `artifactCode`, `spaceId`, `connectorId`, `authProfileCode`, and `principalBindingId`

---

## 5. Ownership Rules For Parallel Waves

### Wave 1

1. Control-plane product surface may rely on frozen names but must not redefine them.
2. Execution persistence owns storage structure for `ExecutionRun`, `ExecutionStep`, `ApprovalRequest`, `AuditEvent`.
3. Credential broker backbone owns the shared resolution interfaces and initial implementations.

### Wave 2

1. Workflow runtime owns execution orchestration.
2. SSE/event worker owns controller-facing adaptation, but not new event names.
3. Credential wiring worker owns lease resolution integration into step execution.

### Shared-file warning

The following files remain orchestrator-owned until a later explicit handoff:

1. `assistant-runtime/.../AssistantAgentFactory.java`
2. `assistant-runtime/.../TenantAwareToolRegistry.java`
3. `assistant-runtime/.../SlotCollectTool.java`
4. `assistant-runtime/.../SlotConfirmTool.java`
5. `assistant-api/.../ChatController.java`
6. `assistant-api/.../SecurityConfig.java`
7. `assistant-execution/.../DAGFlowExecutor.java`

---

## 6. Minimal Code Skeletons Frozen In Wave 0

Wave 0 introduces only shared contract skeletons:

1. `ExecutionLifecycleStatus`
2. `ExecutionEventType`
3. `ExecutionEvent`
4. `CredentialResolutionRequest`
5. `ResolvedCredentialLease`
6. `CredentialBroker`

These skeletons are intentionally implementation-free. They exist only to stop contract drift before parallel workers start coding against them.

---

## 7. Wave 0 Exit Criteria

Wave 0 is complete when:

1. this document is committed to the planning baseline
2. the shared skeleton contracts compile
3. no execution logic has been replaced yet
4. Wave 1 workers can reference the same names without reinterpretation
