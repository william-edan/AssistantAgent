# 2026-03-10 Runtime Publication Source Selection

## Goal

Make `CodeactToolRegistry` source selection happen at invocation time instead of binding the registry to a fixed provider list or a single source path.

## Implemented Model

`ToolPublicationProvider` now exposes three runtime-facing concepts:

- `providerId`: stable source identity, used by runtime source selection.
- `PublicationScope`: carries runtime publication scope plus source-selection directives.
- `SourceSelectionMode`: controls how requested sources are applied.

`PublicationScope` now includes:

- `tenantId`
- `spaceId`
- `environment`
- `agentAppCode`
- `sourceSelectionMode`
- `requestedSourceIds`
- `blockedSourceIds`

## Runtime Rule

A shared `ToolPublicationProviderSelector` is now the only place that decides which providers participate for the current call.

Selection rules:

- `MERGE`: requested sources are placed first, then remaining providers are appended in registration order.
- `EXCLUSIVE`: only requested sources participate.
- `blockedSourceIds`: always excluded.

This selector is now used by both:

- `TenantAwareToolRegistry`
- `ArtifactPublicationLookupService`

That keeps registry publication and artifact lookup on the same source-selection path.

## Context Resolution

`PublicationScopeResolver` now resolves source directives from runtime attributes and state:

- `tool_source_mode`
- `tool_source_ids`
- `disabled_tool_source_ids`

It also accepts camelCase variants.

## Agent App Defaults

`AgentAppPublicationPolicyResolver` now allows an app to define default publication sources through `agent_app_grant`.

Supported grant shapes:

- `target_type=publication_source` with `grant_mode=allow|deny`
- `target_type=publication_source_policy` with `constraints_json`, currently supporting `sourceSelectionMode`

Resolution rules:

- Runtime request attributes still have the highest priority.
- If any explicit `tool_source_*` directive is present, app defaults are ignored for that call.
- If the call does not specify source directives, the resolver falls back to `spaceId + agentAppCode` and loads app defaults from `agent_app_grant`.
- Deny grants win over allow grants for the same provider ID.

This keeps source strategy declarative at the app boundary instead of scattering source defaults across prompt code, controller code, or registry wiring.

## Control-Plane Entry

Publication-source semantics are now also typed on the control-plane side instead of living only as raw `agent_app_grant` rows.

Typed control-plane objects and methods:

- `AgentAppPublicationSourcePolicy`
- `AgentAppGrantService.findPublicationSourcePolicy(agentAppId)`
- `AgentAppGrantService.replacePublicationSourcePolicy(agentAppId, policy)`
- `AgentAppPublicationPolicyService.getPublicationSourcePolicy(spaceCode, environment, agentAppCode)`
- `AgentAppPublicationPolicyService.replacePublicationSourcePolicy(spaceCode, environment, agentAppCode, policy)`

Meaning:

- Control-plane callers no longer need to manually craft `target_type`, `grant_mode`, or `constraints_json` for publication sources.
- Runtime no longer parses raw grant rows directly; it consumes the typed policy exposed by `AgentAppGrantService`.
- Space and app resolution now happen through a dedicated control-plane facade instead of being repeated in API or runtime code.
- The storage model remains backward-compatible because the typed API still compiles down to `agent_app_grant` rows.

This keeps best-route ownership clear: source publication semantics are defined in the control plane, while runtime only resolves and applies them.

## Management API

A secured management entry now exists for publication-source policy administration:

- `GET /api/controlplane/spaces/{spaceCode}/agent-apps/{agentAppCode}/publication-source-policy`
- `PUT /api/controlplane/spaces/{spaceCode}/agent-apps/{agentAppCode}/publication-source-policy`

Behavior:

- `environment` is optional and defaults to `prod`.
- `GET` returns the resolved typed policy, including the default `MERGE + empty lists` view when the app exists but has no custom publication-source grants yet.
- `PUT` replaces the publication-source policy through the typed control-plane API.
- The controller now exposes explicit typed DTOs for request and response payloads instead of nested records and raw `Map` assembly.
- `/api/controlplane/**` now follows the same authenticated API boundary as chat endpoints in migration mode, but the controller also enforces target `space / environment / agentApp` scope authorization.
- Global CORS now also covers `/api/controlplane/**` for local frontend development.

This turns publication-source policy into a real access-plane capability instead of a service-only internal primitive.

## Scoped Local-User Control-Plane Access API

A second typed control-plane management entry now exists for migration-mode admin grants themselves:

- `GET /api/controlplane/spaces/{spaceCode}/local-users/{localUserId}/controlplane-access-policy`
- `PUT /api/controlplane/spaces/{spaceCode}/local-users/{localUserId}/controlplane-access-policy`

Behavior:

- `environment` is optional and defaults to `prod`.
- The API exposes a typed policy view: `spaceAdmin` plus `agentAppAdminCodes`.
- The controller resolves and persists policy through `LocalUserControlPlaneAccessPolicyService` instead of leaking raw `local_user_grant` row semantics.
- Replacement normalizes app codes, validates target app existence inside the target space, removes obsolete scoped grants, and writes the new grant set.
- Authorization is stricter than publication-source management: only global control-plane admin or space admin can manage another local user's control-plane access policy. `agent_app` scoped admin is intentionally excluded.

This keeps migration-mode grant management inside a typed control-plane boundary and avoids pushing raw compatibility grant shapes upward into frontend or application code.

## Provider IDs

Current built-in providers now expose stable IDs:

- `artifact-catalog`
- `legacy-bridge`

Future sources such as MCP, OpenAPI gateway, remote registry, or static local bundles should join by adding a new `ToolPublicationProvider` with a stable `providerId`, instead of modifying registry logic.

## Important Constraint

The selector currently does not enforce provider-level `supportsScope` filtering.

Reason:

The primary goal of this phase is explicit runtime source switching. Provider capability gating should be introduced later as a separate concern, otherwise source selection becomes implicit and hard to reason about.

## Mainline Direction

This keeps the best-route architecture intact:

- `RuntimeArtifact` remains the mainline runtime model.
- `ToolPublicationProvider` remains the source plugin boundary.
- `TenantAwareToolRegistry` remains a scope-aware facade.
- App default source strategy is driven by `AgentAppGrant`, not by registry hardcoding.
- Publication-source policy is owned by the control plane and exposed through typed service APIs.
- Management access is exposed through a dedicated secured control-plane API path.
- Legacy tools remain a provider, not the registry core.

## Files

Main production files in this phase:

- `assistant-controlplane/.../AgentAppPublicationSourcePolicy.java`
- `assistant-controlplane/.../ResolvedAgentAppPublicationSourcePolicy.java`
- `assistant-controlplane/.../AgentAppGrantService.java`
- `assistant-controlplane/.../AgentAppPublicationPolicyService.java`
- `assistant-runtime/.../ToolPublicationProvider.java`
- `assistant-runtime/.../ToolPublicationProviderSelector.java`
- `assistant-runtime/.../AgentAppPublicationPolicyResolver.java`
- `assistant-runtime/.../PublicationScopeResolver.java`
- `assistant-runtime/.../TenantAwareToolRegistry.java`
- `assistant-runtime/.../ArtifactPublicationLookupService.java`
- `assistant-runtime/.../ArtifactCatalogToolPublicationProvider.java`
- `assistant-runtime/.../LegacyToolPublicationProvider.java`
- `assistant-api/.../AgentAppPublicationPolicyController.java`
- `assistant-api/.../SecurityConfig.java`

## Migration Auth Boundary

For migration-mode management APIs, `/api/controlplane/**` is no longer protected by plain "authenticated user" semantics.

Current behavior:

- Local login still uses `local_user_account` as the identity source.
- Management authorization now uses explicit `local_user_grant` rows.
- `local_user_grant` now supports `scope_type / scope_code`, with `global`, `space`, and `agent_app` scope forms.
- `MigrationAuthService` resolves roles and permissions from `local_user_grant`, then merges them with baseline compatibility defaults.
- `TokenIntrospectionAuthenticationFilter` now converts resolved roles and permissions into Spring Security authorities.
- `SecurityConfig` now accepts either the coarse `assistant:controlplane` permission or a control-plane admin role to enter `/api/controlplane/**`.
- `MigrationControlPlaneAuthorizationService` performs the final `space / environment / agentApp` scope check inside the controller boundary.

This keeps the best-route boundary intact:

- Runtime publication and artifact routing remain on the artifact-native mainline.
- Migration auth remains only a compatibility identity and permission boundary for legacy/private deployment entrypoints.
- Control-plane administration is no longer implied by successful login; it requires an explicit grant.

Seed behavior in migration profile currently grants the seeded `admin` user both:

- role: `assistant_controlplane_admin`
- permission: `assistant:controlplane`

This is still a migration-profile compatibility mechanism, not the final enterprise IAM end state.

A real filter-chain integration test now also covers the expected control-plane behavior boundary: missing token -> 401, no control-plane coarse authority -> 403, scoped admin role with matching target scope -> 200, scoped admin role without matching target scope -> 403.




