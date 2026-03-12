# 2026-03-10 Legacy Compatibility Matrix

## Goal

Pin the remaining `legacy-bridge` visibility to explicit compatibility cases now that runtime defaults are moving to artifact-first publication.

## Runtime Rules

| Invocation shape | Default source strategy | `legacy-bridge` visibility | Notes |
| --- | --- | --- | --- |
| Unscoped runtime call (`spaceId` or `agentAppCode` missing) | `MERGE` | visible | migration compatibility entrypoint |
| Scoped app call with no app policy and no explicit source directives | `EXCLUSIVE + artifact-catalog` | hidden | best-route default |
| Scoped app call with `allow_legacy_fallback=true` | `MERGE` with `artifact-catalog` first | visible as fallback | explicit compatibility opt-in |
| Scoped app call with app-level publication-source policy | policy-defined | policy-defined | control-plane owns the default |
| Scoped app call with explicit `tool_source_*` directives | request-defined | request-defined | runtime request has highest priority |
| Scoped app call with explicit `tool_source_ids=[legacy-bridge]` | `EXCLUSIVE` or `MERGE` per request | visible | explicit operator/debug path |

## Observability

`LegacyToolPublicationProvider` now emits structured compatibility logs whenever it is selected:

- `mode=unscoped`
- `mode=explicit_request`
- `mode=fallback`
- `mode=scoped_compatibility`


Runtime fallback warnings are also emitted when scoped artifact-first flows actually degrade to legacy metadata at these entrypoints:

- `ToolCatalogContributor#contribute`
- `AssistantFastIntentHook#resolveBestOperationTarget`
- `AssistantFastIntentHook#resolveExecuteToolName`
- `ToolExecutor#execute`
- `PolicyGuardToolInterceptor#resolveGovernanceRule`
- `SlotCollectTool#resolveToolMetaSnapshot`
- `SlotCollectTool#resolveDependencySteps`

Operational intent:

- `fallback` should trend down as app policies move to artifact-only defaults.
- `scoped_compatibility` should be treated as a migration smell and investigated.
- `explicit_request` is acceptable for controlled debugging or staged cutover.

## Cutover Guidance

1. Prefer app-level publication-source policy over request-time legacy fallback.
2. Treat `allow_legacy_fallback` as a temporary migration control, not a product default.
3. Remove unscoped legacy entrypoints only after artifact-first routing is the default in all UI and API callers.
