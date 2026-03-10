# Enterprise Control Plane Phase 4 Legacy Compiler Bridge Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a first `LegacyCapabilityCompiler` bridge that can compile rows from `assistant_capability_registry` into the new control-plane artifacts and expose a migration-safe path from legacy capability definitions to `interaction_spec`, `action_spec`, `workflow_spec`, and `workflow_step`.

**Architecture:** Phase 4 remains additive but fixes one schema gap before building the compiler: `reference_resolver`, `business_query_action`, `precondition_check`, and `action_spec` need an `operation_binding_json` field so compiled legacy HTTP definitions have a place to store transport bindings. The compiler lives in `assistant-controlplane`, reads legacy rows through `JdbcTemplate`, resolves a target `space` via `PlatformSpaceService`, compiles the row into in-memory artifacts, and upserts those artifacts into the new control-plane tables. The old runtime path still stays on `tool_meta`.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Flyway, Jackson, JUnit 5, Mockito, Maven.

---

### Task 1: Add compiler contract tests

**Files:**
- Create: `assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/compiler/LegacyCapabilityCompilerTest.java`

**Step 1: Write the failing tests**

Write tests for these behaviors:
- `compile(row, spaceId)` compiles a flow legacy capability into one interaction spec, one workflow spec, and workflow step action specs with canonical codes.
- `compileAll("prod")` inserts compiled artifacts when no existing definitions are present.
- `compileAll("prod")` updates existing interaction/action/workflow definitions when matching codes already exist.

**Step 2: Run tests to verify they fail**

Run: `mvn -pl assistant-controlplane "-Dtest=LegacyCapabilityCompilerTest" test`
Expected: build fails because the compiler classes do not exist yet.

**Step 3: Write minimal implementation**

Add `LegacyCapabilityCompiler` and supporting compiled-artifact records, using `JdbcTemplate` + existing control-plane services. Keep scope limited to legacy HTTP capability compilation.

**Step 4: Run tests to verify they pass**

Run: `mvn -pl assistant-controlplane "-Dtest=LegacyCapabilityCompilerTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add legacy capability compiler bridge"
```

### Task 2: Add execution-binding field to definition tables and entities

**Files:**
- Modify: `assistant-infra/src/main/resources/db/migration/V16__create_reference_query_action_tables.sql`
- Modify: `assistant-infra/src/main/resources/db/migration/V17__create_action_and_interaction_tables.sql`
- Modify: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/ReferenceResolver.java`
- Modify: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/BusinessQueryAction.java`
- Modify: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/query/PreconditionCheck.java`
- Modify: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/action/ActionSpec.java`

**Step 1: Add `operation_binding_json` to the schema draft**

Update `V16` and `V17` so the new definition objects can store transport bindings or operation references.

**Step 2: Add `operationBindingJson` fields to the Java entities**

Keep the naming consistent with the rest of the schema-to-entity mapping.

**Step 3: Run compile to verify the updated entities still build**

Run: `mvn -pl assistant-controlplane,assistant-infra -am -DskipTests compile`
Expected: PASS.

**Step 4: Commit**

```bash
git add assistant-infra/src/main/resources/db/migration assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane
git commit -m "feat(controlplane): add operation binding to definition models"
```

### Task 3: Implement `LegacyCapabilityCompiler`

**Files:**
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/compiler/LegacyCapabilityCompiler.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/compiler/CompiledLegacyCapability.java`
- Create: `assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/compiler/CompiledLegacyAction.java`

**Step 1: Implement compile-time mapping rules**

Map a legacy capability row as follows:
- canonical base code: `systemCode.capabilityCode`
- interaction code: `baseCode + ".interaction"`
- simple capability action code: `baseCode`
- flow-step action code: `baseCode + "." + stepId`
- workflow code: `baseCode`

**Step 2: Implement operation binding serialization**

Store legacy HTTP transport details into `operationBindingJson` with a stable JSON shape including binding type, system code, endpoint, method, content type, input mapping, output mapping, and success condition when present.

**Step 3: Implement compile-all upsert flow**

Use `PlatformSpaceService` to resolve `tenant_id -> space_id`, then upsert interaction specs, action specs, workflow specs, and workflow steps.

**Step 4: Run the compiler test**

Run: `mvn -pl assistant-controlplane "-Dtest=LegacyCapabilityCompilerTest" test`
Expected: PASS.

**Step 5: Commit**

```bash
git add assistant-controlplane/src/main/java/com/alibaba/assistant/agent/controlplane/compiler assistant-controlplane/src/test/java/com/alibaba/assistant/agent/controlplane/compiler
git commit -m "feat(controlplane): compile legacy capability to new artifacts"
```

### Task 4: Verify Phase 4 remains compatible with the current runtime

**Files:**
- Verify only; no required new files.

**Step 1: Run focused control-plane tests**

Run: `mvn -pl assistant-controlplane "-Dtest=ToolMetaServiceContractTest,CapabilityToToolMetaMigratorTest,PlatformSpaceServiceContractTest,ConnectorServiceContractTest,AuthProfileServiceContractTest,PrincipalBindingV2ServiceContractTest,ReferenceResolverServiceContractTest,BusinessQueryActionServiceContractTest,PreconditionCheckServiceContractTest,ActionSpecServiceContractTest,InteractionSpecServiceContractTest,WorkflowSpecServiceContractTest,WorkflowStepServiceContractTest,LegacyCapabilityCompilerTest" test`
Expected: PASS.

**Step 2: Run compile for dependent modules**

Run: `mvn -pl assistant-controlplane,assistant-runtime,assistant-execution -am -DskipTests compile`
Expected: PASS.

**Step 3: Review compatibility boundaries**

Confirm that runtime is still not consuming the new control-plane workflow tables directly. The compiler bridge only prepares artifacts and stores definitions.

**Step 4: Commit**

```bash
git add .
git commit -m "test: verify phase4 compiler bridge baseline"
```
