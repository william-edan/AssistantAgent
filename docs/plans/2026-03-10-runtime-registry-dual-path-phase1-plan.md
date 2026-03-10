> **Status:** Superseded on 2026-03-10. Replaced by docs/plans/2026-03-10-runtime-artifact-publication-backbone-plan.md because the user chose the long-term best-route architecture instead of the legacy bridge-first path.

# Runtime Registry Dual-Path Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Introduce a feature-flagged dual-path bridge in runtime tool loading so legacy `tool_meta` and new control-plane artifacts can both participate in registry construction, while keeping execution on the existing `CapabilityBridgeTool` path.

**Architecture:** Phase 8 stays inside `assistant-runtime`. `CapabilityBridgeToolFactory` remains the only producer of Codeact tools, but it gains an optional catalog source controlled by `assistant.runtime.registry.*` flags. New runtime artifacts are first adapted into synthesized `ToolMeta` rows, then passed through the existing `CapabilityBridgeTool` creation path. To avoid overstating support, only single-system artifacts are admitted in this phase; artifacts whose actions resolve to more than one downstream `systemCode` are skipped.

**Tech Stack:** Java 17, Spring Boot 3, Jackson, JUnit 5, Mockito, Maven.

---

### Task 1: Add bridge factory tests first

**Files:**
- Modify: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/tool/codeact/CapabilityBridgeToolFactoryTest.java`

**Step 1: Write the failing tests**

Cover these minimal behaviors:
- legacy mode still loads tools only from `tool_meta`
- dual mode appends catalog artifacts from `RuntimeArtifactCatalogService`
- dual/catalog mode skips artifacts that resolve to multiple `systemCode` values

**Step 2: Run the focused test to verify it fails**

Run: `mvn -pl assistant-runtime -am "-Dtest=CapabilityBridgeToolFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because the factory has no registry feature flag or catalog integration yet.

**Step 3: Implement the minimal production code**

Add only the property fields and bridge logic needed to make the tests pass.

**Step 4: Re-run the test to verify it passes**

Run: `mvn -pl assistant-runtime -am "-Dtest=CapabilityBridgeToolFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

### Task 2: Add registry feature flags and artifact adaptation

**Files:**
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/config/AssistantRuntimeProperties.java`
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/tool/codeact/CapabilityBridgeToolFactory.java`

**Step 1: Add registry config**

Introduce a nested `Registry` property group with:
- `artifactSourceMode` default `legacy`
- `catalogAgentAppCode`
- `spaceEnvironment` default `prod`

**Step 2: Add catalog loading path**

When mode is `dual` or `catalog`, the factory should:
- resolve `tenantId -> platform space`
- load granted runtime artifacts for the configured agent app
- adapt each supported artifact into synthetic `ToolMeta`
- merge with legacy `ToolMeta` rows when mode is `dual`

**Step 3: Enforce current safety boundary**

When adapting runtime artifacts, admit only artifacts whose action set resolves to exactly one downstream `systemCode`. Skip multi-system artifacts and log the reason.

**Step 4: Preserve the existing bridge path**

All adapted artifacts should still be instantiated through `CapabilityBridgeTool`, not through a second tool implementation.

### Task 3: Verify compatibility

**Files:**
- Create: `docs/plans/2026-03-10-runtime-registry-dual-path-phase1-plan.md`
- Verify only; no other files required.

**Step 1: Run focused tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=CapabilityBridgeToolFactoryTest,RuntimeArtifactCatalogServiceTest,RuntimeArtifactCompilerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

**Step 2: Run registry/runtime smoke tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=TenantAwareToolRegistryTest,CapabilityBridgeToolFactoryTest,RuntimeArtifactCatalogServiceTest,RuntimeArtifactCompilerTest,DependencyResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

**Step 3: Run cross-module compile**

Run: `mvn -pl assistant-runtime,assistant-controlplane,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 4: Review boundary**

Confirm that this phase only changes tool discovery. Step-level connector/auth execution still remains on the legacy `ToolMeta/systemCode` bridge, so multi-system artifacts remain intentionally excluded from registry publication.

