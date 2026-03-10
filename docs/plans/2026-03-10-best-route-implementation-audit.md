# 最佳路线实现审计

> 日期：2026-03-10
> 审计范围：截至当前会话已经落地的 enterprise control-plane / runtime compiler / runtime catalog 相关实现
> 结论用途：为后续实现提供“保留 / 限制 / 废弃”依据
> 配套治理文档：`docs/plans/2026-03-10-best-route-implementation-governance.md`

---

## 1. 审计结论摘要

当前已落地实现总体上可以分成三类：

1. `Aligned`：新控制面和 artifact 主线上的实现，方向正确，应继续积累。
2. `Transitional`：为迁移和兼容保留的旧边界或桥接实现，可以继续存在，但禁止继续做主能力扩展。
3. `Superseded`：方向已经被新治理决议否定，不得继续沿该方向实施。

整体判断：

1. 已完成的 control-plane Phase 1-5 基本符合最佳路线。
2. 已完成的 runtime compiler / runtime catalog 基本符合最佳路线。
3. 当前 legacy runtime bridge 仍然存在，但只能作为过渡边界。
4. 已写出的“runtime registry dual-path”方案已废弃，不能作为后续实现依据。

---

## 2. Aligned：已对齐最佳路线的实现

### 2.1 新控制面表结构与服务层

状态：`Aligned`

范围包括：

1. `V13__create_platform_space.sql`
2. `V14__create_connector_and_auth_profile.sql`
3. `V15__create_principal_binding_v2.sql`
4. `V16__create_reference_query_action_tables.sql`
5. `V17__create_action_and_interaction_tables.sql`
6. `V18__create_workflow_tables.sql`
7. `V19__create_agent_app_tables.sql`

以及对应 Java 包：

1. `assistant-controlplane/.../space`
2. `assistant-controlplane/.../connector`
3. `assistant-controlplane/.../identity`
4. `assistant-controlplane/.../query`
5. `assistant-controlplane/.../action`
6. `assistant-controlplane/.../interaction`
7. `assistant-controlplane/.../workflow`
8. `assistant-controlplane/.../agentapp`

审计结论：

1. 这些实现已经把控制面从 `ToolMeta` 中心模型拆开。
2. 这些对象与最佳路线下的领域模型一致。
3. 后续应继续在这条主线上补发布、审批、风险和执行时契约，而不是回退到 capability 大表模式。

### 2.2 `LegacyCapabilityCompiler`

状态：`Aligned (with compatibility role)`

范围：

1. `assistant-controlplane/.../compiler/LegacyCapabilityCompiler.java`
2. `assistant-controlplane/.../compiler/CompiledLegacyCapability.java`

审计结论：

1. 兼容方向是“旧 capability -> 新控制面对象”，方向正确。
2. 它没有把新模型重新定义成旧模型，而是把旧模型拆到新模型中。
3. 后续允许继续增强，但增强方向只能是“提高旧数据到新模型的编译完整度”，不能反过来让它成为新模型回写 legacy 主模型的长期核心。

### 2.3 `RuntimeArtifactCompiler`

状态：`Aligned`

范围：

1. `assistant-runtime/.../compiler/RuntimeArtifact.java`
2. `assistant-runtime/.../compiler/RuntimeArtifactCompiler.java`
3. `assistant-runtime/.../compiler/RuntimeArtifactCompilerTest.java`

审计结论：

1. 该实现已经以 `RuntimeArtifact` 作为运行时产物，而不是以 `ToolMeta` 作为运行时主模型。
2. 它直接消费 `workflow/action/interaction` 新控制面对象，符合最佳路线。
3. 后续可继续演进为 publication backbone、workflow runtime、event contract 的基础层。

### 2.4 `RuntimeArtifactCatalogService`

状态：`Aligned`

范围：

1. `assistant-runtime/.../registry/RuntimeArtifactCatalogService.java`
2. `assistant-runtime/.../registry/RuntimeArtifactCatalogServiceTest.java`

审计结论：

1. 它已经基于 `WorkflowSpec / WorkflowStep / ActionSpec / InteractionSpec / AgentAppGrant` 装载 runtime artifact。
2. 该方向是 artifact-native runtime 的正确入口。
3. 后续应围绕它继续构建 publication provider，而不是把它退化成 `ToolMeta` 生成器。

### 2.5 文档主线

状态：`Aligned`

范围：

1. `2026-03-10-enterprise-private-openclaw-platform-design.md`
2. `2026-03-10-enterprise-control-plane-schema-and-runtime-design.md`
3. `2026-03-10-enterprise-physical-ddl-java-model-and-migration-plan.md`
4. `2026-03-10-runtime-artifact-compiler-phase1-plan.md`
5. `2026-03-10-runtime-artifact-catalog-phase1-plan.md`
6. `2026-03-10-runtime-artifact-publication-backbone-plan.md`

审计结论：

1. 这些文档已经把“新控制面对象 -> runtime artifact -> provider-driven registry -> workflow runtime”定义为正确方向。
2. 其中 publication backbone 文档是 runtime 后续实现的直接基线。

### 2.6 migration 管理权限收口与 typed management API

状态：`Aligned (compatibility boundary)`

范围：

1. `V20__create_local_user_grant.sql`
2. `V21__add_scope_to_local_user_grant.sql`
3. `assistant-controlplane/.../identity/LocalUserGrant.java`
4. `assistant-controlplane/.../identity/LocalUserGrantService.java`
5. `assistant-api/.../security/MigrationAuthService.java`
6. `assistant-api/.../security/MigrationControlPlaneAuthorizationService.java`
7. `assistant-api/.../security/TokenIntrospectionAuthenticationFilter.java`
8. `assistant-api/.../security/SecurityConfig.java`
9. `assistant-api/.../controller/AgentAppPublicationPolicyController.java`
10. `assistant-api/.../controller/dto/AgentAppPublicationSourcePolicyRequest.java`
11. `assistant-api/.../controller/dto/AgentAppPublicationSourcePolicyData.java`
12. `assistant-api/.../controller/dto/AgentAppPublicationSourcePolicyResponse.java`
13. `assistant-api/.../security/AgentAppPublicationPolicySecurityIntegrationTest.java`
14. `assistant-api/.../security/MigrationControlPlaneAuthorizationServiceTest.java`
15. `assistant-controlplane/.../identity/LocalUserControlPlaneAccessPolicy.java`
16. `assistant-controlplane/.../identity/ResolvedLocalUserControlPlaneAccessPolicy.java`
17. `assistant-controlplane/.../identity/LocalUserControlPlaneAccessPolicyService.java`
18. `assistant-api/.../controller/LocalUserControlPlaneAccessPolicyController.java`
19. `assistant-api/.../controller/dto/LocalUserControlPlaneAccessPolicyRequest.java`
20. `assistant-api/.../controller/dto/LocalUserControlPlaneAccessPolicyData.java`
21. `assistant-api/.../controller/dto/LocalUserControlPlaneAccessPolicyResponse.java`
22. `assistant-api/.../security/LocalUserControlPlaneAccessPolicySecurityIntegrationTest.java`

审计结论：

1. 这批实现把 control-plane 管理权限从“登录即默认可管理”收紧为显式 `local_user_grant`，并引入 `scope_type / scope_code` 表达 `global / space / agent_app` 作用域，方向正确。
2. 它们属于 migration/profile 私有化兼容边界，不属于 artifact/runtime 主线，因此不触碰 legacy capability bridge 是正确做法。
3. `SecurityConfig` 现在只负责 control-plane 粗粒度入口 gate，而 `MigrationControlPlaneAuthorizationService` 负责最终 `space / app` scope 判定，边界清晰。
4. 管理 API 已经通过 typed DTO 暴露 publication-source policy，而不是把底层 grant 行语义直接泄露给调用方，符合最佳路线下的 control-plane ownership。
5. 真实 filter-chain 的 `401 / 403 / 200` 行为已经有集成测试覆盖，其中 `agent_app` scoped admin role 无需依赖全局 control-plane permission 也能通过粗 gate，再由 controller 做最终 scope 判定，可作为后续权限收紧的回归基线。
6. `local_user_grant` 已经不再只能通过 seed 或直接改库维护；当前新增的 typed local-user access policy service / controller 把 `space admin` 与 `agent app admin` 的 grant 组合收敛成显式控制面能力，方向正确。
7. 该 typed local-user policy 仍然属于 migration compatibility boundary，而不是最终 IAM 模型；它的价值在于避免上层 API 或前端重新操作原始 grant 语义。
8. 当前授权边界也已明确：只有 global admin 或 space admin 可以管理其他 local user 的 control-plane access policy，agent-app scoped admin 不能提升或转授这类权限。

---

## 3. Transitional：允许保留，但禁止继续扩张的实现

### 3.1 `ToolMeta` 与 `ToolMetaService`

状态：`Transitional`

范围：

1. `assistant-controlplane/.../toolregistry/ToolMeta.java`
2. `assistant-controlplane/.../toolregistry/ToolMetaService.java`
3. 相关 `tool_meta` mapper / migrator / contract tests

审计结论：

1. 它们仍然承担旧运行时和旧功能兼容职责。
2. 允许保留，但不应继续承接新的控制面主语义。
3. 后续只允许：bugfix、兼容查询、legacy 发布适配。
4. 后续不允许：新增业务能力直接先落 `ToolMeta` 再反推回新模型。

### 3.2 `CapabilityBridgeToolFactory`

状态：`Transitional`

范围：

1. `assistant-runtime/.../tool/codeact/CapabilityBridgeToolFactory.java`
2. `assistant-runtime/.../tool/codeact/CapabilityBridgeToolFactoryTest.java`

审计结论：

1. 这是旧 runtime publication path 的核心工厂。
2. 它目前仍是运行时兼容主路径的一部分，因此不能立即删除。
3. 但它不应再被增强为未来主 registry/factory 骨干。
4. 后续只允许：被 `LegacyToolPublicationProvider` 收编、兼容旧 tool 发布、必要 bugfix。
5. 不允许：继续在其中叠加新 artifact publication 的主逻辑。

### 3.3 `CapabilityBridgeTool`

状态：`Transitional`

范围：

1. `assistant-runtime/.../tool/codeact/CapabilityBridgeTool.java`

审计结论：

1. 它目前仍然承接 legacy execution bridge 的执行逻辑。
2. 里面对 `ToolMeta.systemCode` 和旧 flow 执行路径仍有较强依赖。
3. 后续可以继续用于迁移阶段兼容执行，但不能作为最终 artifact-native execution contract 的承载体。
4. 多系统、多 connector、多 authProfile 的最终语义不应长期寄存在这个类里。

### 3.4 `TenantAwareToolRegistry`

状态：`Transitional`

范围：

1. `assistant-runtime/.../registry/TenantAwareToolRegistry.java`
2. `assistant-runtime/.../registry/TenantAwareToolRegistryTest.java`

审计结论：

1. 它的缓存、快照、失效机制是有价值的，可以保留。
2. 但它当前仍依赖具体 legacy factory 作为单一来源。
3. 后续应升级为 provider-driven registry，而不是继续绑定 legacy publication path。
4. 允许保留缓存框架，不允许继续维持“一个 legacy factory 即 registry 主入口”的结构不变。

### 3.5 `migration` profile 下的旧接口与旧认证

状态：`Transitional`

审计结论：

1. 它们是迁移兼容边界，不是最终产品内核。
2. 后续仍可保留以兼容旧前端与旧部署，但不应继续演进为主产品抽象。

---

## 4. Superseded：已废弃方向

### 4.1 runtime registry dual-path on legacy bridge

状态：`Superseded`

范围：

1. `docs/plans/2026-03-10-runtime-registry-dual-path-phase1-plan.md`

审计结论：

1. 该方向的核心思路是“新 artifact -> 伪装成 ToolMeta -> 继续通过旧 bridge 发布”。
2. 这与当前已确认的最佳路线冲突。
3. 该文档只能保留为历史记录，不得继续作为实施依据。

---

## 5. 对后续实现的明确要求

从本审计结论出发，后续所有实现必须遵守：

1. 优先扩展 `RuntimeArtifactCompiler / RuntimeArtifactCatalogService / publication backbone`，而不是扩展 `CapabilityBridgeToolFactory`。
2. 优先让 registry 面向 provider 和 published descriptor，不能继续面向单一 legacy factory。
3. 若必须触碰 `ToolMeta`、`CapabilityBridgeToolFactory`、`CapabilityBridgeTool`、`TenantAwareToolRegistry`，提交内容必须能明确解释其兼容目的。
4. 不得新增任何把新控制面语义折叠回 `ToolMeta` 作为主运行时契约的设计。
5. 后续 phase 文档必须明确标注自己属于：
   - `best-route mainline`
   - `legacy compatibility only`
   - `superseded`

---

## 6. 当前审计后的推荐主线

当前推荐继续推进的主线是：

1. `RuntimeArtifact` publication backbone
2. `ToolPublicationProvider` 抽象
3. `ArtifactToolFactory`
4. `LegacyToolPublicationProvider`
5. provider-driven `TenantAwareToolRegistry`
6. artifact-native workflow runtime
7. `CredentialBroker` 与 execution event contract

---

## 7. 一句话结论

截至当前，已经落地的 enterprise control-plane 和 runtime artifact/compiler/catalog 基础层总体上是对的；真正需要严格收口的是 legacy runtime bridge，只能继续作为过渡边界，不能再被扩展成未来主干。




