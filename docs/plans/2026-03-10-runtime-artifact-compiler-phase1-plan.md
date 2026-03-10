> **Status:** Aligned foundational phase under the best-route architecture.

# Runtime Artifact Compiler Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a runtime-side compiler that converts the new control-plane `workflow_spec` / `workflow_step` / `action_spec` / `interaction_spec` definitions into stable runtime artifacts without switching the existing execution path yet.

**Architecture:** Phase 6 stays additive and runtime-local. `assistant-runtime` gains a `compiler` package with a `RuntimeArtifact` aggregate and a `RuntimeArtifactCompiler` that derives a `FlowDefinition`, preserves step-level auth/policy metadata, and keeps action/interaction bindings available for later registry and execution integration. This phase does not load from the database directly and does not replace `CapabilityBridgeTool`; it only establishes the in-memory contract future runtime loaders will consume.

**Tech Stack:** Java 17, Spring Boot 3, Jackson, JUnit 5, Maven.

---

### Task 1: Add runtime artifact compiler tests first

**Files:**
- Create: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/compiler/RuntimeArtifactCompilerTest.java`

**Step 1: Write the failing tests**

Cover these minimal behaviors:
- `compileWorkflow(...)` builds a runtime artifact with a derived `FlowDefinition`, entry nodes, terminal nodes, reverse `next` edges, and step/action metadata preserved.
- `compileAction(...)` wraps a single `ActionSpec` into a one-step runtime artifact.
- `compileWorkflow(...)` fails fast when a HTTP workflow step points at a missing `ActionSpec`.

**Step 2: Run the test to verify it fails**

Run: `mvn -pl assistant-runtime -am "-Dtest=RuntimeArtifactCompilerTest" test`
Expected: build fails because the new compiler classes do not exist yet.

**Step 3: Write the minimal implementation**

Create the runtime artifact model and compiler with only the fields and helpers required by the failing tests.

**Step 4: Re-run the test to verify it passes**

Run: `mvn -pl assistant-runtime -am "-Dtest=RuntimeArtifactCompilerTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/compiler assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/compiler
git commit -m "feat(runtime): add runtime artifact compiler baseline"
```

### Task 2: Add the runtime compiler model package

**Files:**
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/compiler/RuntimeArtifact.java`
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/compiler/RuntimeArtifactCompiler.java`

**Step 1: Model the runtime artifact aggregate**

`RuntimeArtifact` should keep:
- artifact identity (`artifactCode`, `artifactType`, `spaceId`, `version`, `displayName`)
- optional interaction payload (`interactionCode`, slot/ask/confirm/edit JSON)
- compiled `FlowDefinition`
- action catalog keyed by `actionCode`
- step catalog keyed by `stepId`
- workflow-level failure/audit/risk/approval policy payloads

**Step 2: Compile workflow definitions**

Implement `compileWorkflow(...)` so it:
- sorts steps by `stepOrder`
- parses `dependsOnJson`
- derives `entry`, `terminal`, and `next`
- maps step types to `StepType`
- compiles HTTP step config from `ActionSpec.operationBindingJson` plus step input/output mappings
- preserves raw step policy/auth JSON in the runtime step view

**Step 3: Compile standalone actions**

Implement `compileAction(...)` as a single-step flow artifact using the action binding as the step config.

**Step 4: Keep behavior additive**

Do not wire this compiler into `TenantAwareToolRegistry`, `CapabilityBridgeToolFactory`, or any live execution path in this phase.

**Step 5: Commit**

```bash
git add assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/compiler
git commit -m "feat(runtime): add runtime artifact model"
```

### Task 3: Verify compile compatibility across modules

**Files:**
- Create: `docs/plans/2026-03-10-runtime-artifact-compiler-phase1-plan.md`
- Verify only; no other required files.

**Step 1: Run focused runtime tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=RuntimeArtifactCompilerTest" test`
Expected: PASS.

**Step 2: Run existing runtime smoke tests around touched dependencies**

Run: `mvn -pl assistant-runtime -am "-Dtest=TenantAwareToolRegistryTest,DependencyResolverTest,RuntimeArtifactCompilerTest" test`
Expected: PASS.

**Step 3: Run cross-module compile**

Run: `mvn -pl assistant-runtime,assistant-controlplane,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 4: Review compatibility boundary**

Confirm this phase only introduces a reusable compiler contract. Runtime registration, app-grant enforcement, auth resolution, and execution event streaming remain for later phases.

**Step 5: Commit**

```bash
git add docs/plans/2026-03-10-runtime-artifact-compiler-phase1-plan.md assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/compiler assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/compiler
git commit -m "test(runtime): verify runtime artifact compiler phase1"
```


