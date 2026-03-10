> **Status:** Mainline best-route implementation plan. All future runtime publication work should inherit this direction.

# Runtime Artifact Publication Backbone Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the legacy `ToolMeta`-centered runtime publication path with an artifact-native publication backbone where `RuntimeArtifact` is the primary runtime model and legacy `tool_meta` becomes an adapter input, not the main execution contract.

**Architecture:** The new backbone introduces three stable runtime roles. `ToolPublicationProvider` becomes the registry-facing source interface. `ArtifactToolFactory` becomes the primary publisher that turns `RuntimeArtifact` into user-visible tools without first down-translating back to `ToolMeta`. `LegacyToolPublicationProvider` adapts old `tool_meta` records into the same publication contract so migration traffic can coexist during transition. `TenantAwareToolRegistry` depends on publication providers rather than a concrete legacy bridge factory. This keeps the new runtime model primary and moves legacy compatibility to the edge where it belongs.

**Tech Stack:** Java 17, Spring Boot 3, Jackson, JUnit 5, Mockito, Maven.

---

### Task 1: Define the artifact-native publication contract first

**Files:**
- Create: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/registry/ToolPublicationProviderTest.java`
- Create: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/tool/codeact/ArtifactToolFactoryTest.java`

**Step 1: Write the failing tests**

Cover these minimal behaviors:
- a publication provider can list runtime-published tool descriptors for a tenant/space scope
- `ArtifactToolFactory` can publish a workflow artifact and an action artifact without converting them to `ToolMeta`
- tool names and prompt metadata remain deterministic and collision-safe

**Step 2: Run the focused tests to verify they fail**

Run: `mvn -pl assistant-runtime -am "-Dtest=ToolPublicationProviderTest,ArtifactToolFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because the publication abstraction and artifact-native tool factory do not exist yet.

**Step 3: Implement the minimal production contract**

Add only the provider interface, publication descriptor model, and artifact-native factory pieces needed to satisfy the tests.

**Step 4: Re-run the tests to verify they pass**

Run: `mvn -pl assistant-runtime -am "-Dtest=ToolPublicationProviderTest,ArtifactToolFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

### Task 2: Add the runtime publication backbone

**Files:**
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/ToolPublicationProvider.java`
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/PublishedToolDescriptor.java`
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/tool/codeact/ArtifactToolFactory.java`
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/tool/codeact/ArtifactBackedCodeactTool.java`

**Step 1: Make `RuntimeArtifact` the publication input**

`ArtifactToolFactory` must accept `RuntimeArtifact` directly and publish tools from:
- artifact identity
- interaction contract
- workflow/action execution contract
- resolved connector/system binding metadata required for current execution compatibility

**Step 2: Stop using `ToolMeta` as the internal publication model**

The artifact-native publication path must not synthesize `ToolMeta` objects as an intermediate step.

**Step 3: Keep execution compatibility explicit**

If the current executor still requires `systemCode`-style context, represent that as an explicit compatibility field on the published descriptor or artifact binding. Do not hide it in a fake `ToolMeta` back-conversion.

### Task 3: Move legacy compatibility to an adapter provider

**Files:**
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/LegacyToolPublicationProvider.java`
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/tool/codeact/CapabilityBridgeToolFactory.java`

**Step 1: Introduce a legacy provider**

Wrap current `tool_meta` lookup behind `LegacyToolPublicationProvider` so legacy publication becomes one provider among several.

**Step 2: Narrow `CapabilityBridgeToolFactory` responsibilities**

Retain it only as a legacy adapter or remove it from the primary publication path once the artifact-native factory is in place.

**Step 3: Preserve migration compatibility**

Legacy tools should still be publishable, but only through the provider abstraction.

### Task 4: Switch `TenantAwareToolRegistry` to provider-driven loading

**Files:**
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/TenantAwareToolRegistry.java`
- Modify: `assistant-runtime/src/test/java/com/alibaba/assistant/agent/runtime/registry/TenantAwareToolRegistryTest.java`

**Step 1: Depend on providers, not one concrete legacy factory**

The registry should aggregate descriptors from configured `ToolPublicationProvider` instances, then materialize tools through the correct factory.

**Step 2: Preserve snapshot caching and invalidation**

Existing cache behavior stays unchanged.

**Step 3: Keep deterministic ordering**

Provider order and descriptor order must be stable so tool prompts do not drift between identical loads.

### Task 5: Add the best-route artifact provider

**Files:**
- Create: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/ArtifactCatalogPublicationProvider.java`
- Modify: `assistant-runtime/src/main/java/com/alibaba/assistant/agent/runtime/registry/RuntimeArtifactCatalogService.java`

**Step 1: Publish from new control-plane artifacts directly**

This provider should load from the new artifact catalog and hand artifacts to `ArtifactToolFactory`.

**Step 2: Keep current execution boundary honest**

Until the new workflow runtime fully replaces the legacy executor, only publish artifacts whose execution compatibility contract is explicit and satisfiable. Unsupported multi-system artifacts should remain catalog-visible but not registry-published.

### Task 6: Verify the backbone baseline

**Files:**
- Create: `docs/plans/2026-03-10-runtime-artifact-publication-backbone-plan.md`
- Verify only; no other files required.

**Step 1: Run focused backbone tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=ToolPublicationProviderTest,ArtifactToolFactoryTest,RuntimeArtifactCatalogServiceTest,CapabilityBridgeToolFactoryTest,TenantAwareToolRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

**Step 2: Run runtime smoke tests**

Run: `mvn -pl assistant-runtime -am "-Dtest=DependencyResolverTest,RuntimeArtifactCompilerTest,RuntimeArtifactCatalogServiceTest,ToolPublicationProviderTest,ArtifactToolFactoryTest,CapabilityBridgeToolFactoryTest,TenantAwareToolRegistryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS.

**Step 3: Run cross-module compile**

Run: `mvn -pl assistant-runtime,assistant-controlplane,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 4: Review boundary**

Confirm the new backbone makes `RuntimeArtifact` primary, keeps legacy in an adapter provider, and avoids synthesizing `ToolMeta` as the main runtime publication model.

