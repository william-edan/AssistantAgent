> **Status:** Aligned foundational phase under the best-route architecture.

# Runtime Artifact Catalog Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a runtime-side catalog service that can load compiled runtime artifacts from the new control-plane definitions by `workflowCode`, `actionCode`, and `agentApp` grants without changing the existing execution path.

**Architecture:** Phase 7 stays additive and service-level. `assistant-runtime` gains a catalog service in `runtime.registry` that coordinates `WorkflowSpecService`, `WorkflowStepService`, `ActionSpecService`, `InteractionSpecService`, `AgentAppService`, and `AgentAppGrantService`, then delegates compilation to `RuntimeArtifactCompiler`. The service only supports `workflow` and `action` grants in this phase and intentionally ignores connectors, queries, and deny constraints beyond direct target exclusion.

**Tech Stack:** Java 17, Spring Boot 3, Mockito, JUnit 5, Maven.

---

### Task 1: Add runtime artifact catalog tests first

**Files:**
- Create: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/registry/RuntimeArtifactCatalogServiceTest.java`

**Step 1: Write the failing tests**

Cover these minimal behaviors:
- `loadWorkflowArtifact(...)` loads workflow, steps, interaction, actions, and delegates to `RuntimeArtifactCompiler`.
- `loadActionArtifact(...)` loads a single action and delegates to `RuntimeArtifactCompiler` with no interaction.
- `listGrantedArtifacts(...)` resolves an active agent app, applies allow/deny grant precedence for `workflow` and `action`, and returns compiled artifacts in grant order.

**Step 2: Run the test to verify it fails**

Run: `mvn -pl assistant-runtime -am "-Dtest=RuntimeArtifactCatalogServiceTest" test`
Expected: build fails because the new runtime catalog service does not exist yet.

**Step 3: Write the minimal implementation**

Create only the catalog service and helpers needed by the failing tests.

**Step 4: Re-run the test to verify it passes**

Run: `mvn -pl assistant-runtime -am "-Dtest=RuntimeArtifactCatalogServiceTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/registry assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry
git commit -m "feat(runtime): add runtime artifact catalog service"
```

### Task 2: Add the runtime catalog service

**Files:**
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/RuntimeArtifactCatalogService.java`

**Step 1: Implement workflow loading**

`loadWorkflowArtifact(spaceId, workflowCode)` should:
- return empty for blank input
- fetch the latest enabled workflow
- fetch enabled steps by workflow id
- fetch interaction by `interactionSpecId` when present
- resolve action refs from step targets
- delegate to `RuntimeArtifactCompiler.compileWorkflow(...)`

**Step 2: Implement action loading**

`loadActionArtifact(spaceId, actionCode)` should:
- return empty for blank input
- fetch the latest enabled action
- delegate to `RuntimeArtifactCompiler.compileAction(...)`

**Step 3: Implement app grant loading**

`listGrantedArtifacts(spaceId, agentAppCode)` should:
- resolve the active app
- load grants by app id
- keep only `allow` grants for `workflow` and `action`
- apply direct `deny` target exclusions
- preserve grant order while deduplicating exact targets
- call the workflow/action loading helpers and skip missing targets

**Step 4: Keep behavior additive**

Do not wire this catalog into `TenantAwareToolRegistry` in this phase.

**Step 5: Commit**

```bash
git add assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/RuntimeArtifactCatalogService.java
git commit -m "feat(runtime): add catalog loading for new artifacts"
```

### Task 3: Verify compatibility around the new loader

**Files:**
- Create: `docs/plans/2026-03-10-runtime-artifact-catalog-phase1-plan.md`
- Verify only; no other required files.

**Step 1: Run focused tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=RuntimeArtifactCatalogServiceTest,RuntimeArtifactCompilerTest" test`
Expected: PASS.

**Step 2: Run runtime smoke tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=TenantAwareToolRegistryTest,DependencyResolverTest,RuntimeArtifactCompilerTest,RuntimeArtifactCatalogServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

**Step 3: Run cross-module compile**

Run: `mvn -pl assistant-runtime,assistant-controlplane,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 4: Review boundary**

Confirm catalog loading is available, but no runtime registry or execution path has switched to it yet.

**Step 5: Commit**

```bash
git add docs/plans/2026-03-10-runtime-artifact-catalog-phase1-plan.md assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/RuntimeArtifactCatalogService.java assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/registry/RuntimeArtifactCatalogServiceTest.java
git commit -m "test(runtime): verify runtime artifact catalog phase1"
```

