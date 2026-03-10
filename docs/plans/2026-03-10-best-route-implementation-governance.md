# 最佳路线实施治理决议

> 日期：2026-03-10
> 状态：Effective
> 适用范围：`D:/devfive/AssistantAgent` 全部后续设计、编码、迁移、重构工作
> 关联文档：
> - `docs/plans/2026-03-10-enterprise-private-openclaw-platform-design.md`
> - `docs/plans/2026-03-10-enterprise-control-plane-schema-and-runtime-design.md`
> - `docs/plans/2026-03-10-enterprise-physical-ddl-java-model-and-migration-plan.md`
> - `docs/plans/2026-03-10-runtime-artifact-publication-backbone-plan.md`

---

## 1. 决议结论

自 2026-03-10 起，本仓库后续所有新增实现、结构调整、运行时替换、控制面扩展，都必须以“最佳路线”作为唯一主线。

这里的“最佳路线”含义明确如下：

1. `RuntimeArtifact` 是运行时一等模型。
2. `Connector / AuthProfile / PrincipalBinding / ReferenceResolver / BusinessQueryAction / PreconditionCheck / ActionSpec / InteractionSpec / WorkflowSpec / AgentAppGrant` 是控制面一等模型。
3. legacy `tool_meta / assistant_capability_registry / systemCode` 只能作为兼容输入或兼容边界，不能继续作为产品主模型。
4. 新模型可以兼容旧模型，但兼容方向必须是“旧 -> 新”，不能是“新 -> 旧”作为长期主路径。
5. 所有后续 runtime publication、registry、execution runtime、approval、audit 演进，都必须围绕 artifact-native 架构推进。

一句话定稿：

`后续实现必须以新控制面对象和 RuntimeArtifact 为中心，legacy 仅允许作为适配层存在。`

---

## 2. 强制实施原则

### 2.1 控制面主模型原则

禁止再把新增能力直接设计成：

`ToolMeta + executionPlan + interactionPolicy + systemCode`

后续新增能力必须先映射到：

1. `Connector`
2. `AuthProfile`
3. `PrincipalBinding`
4. `ReferenceResolver / BusinessQueryAction / PreconditionCheck`
5. `ActionSpec`
6. `InteractionSpec`
7. `WorkflowSpec / WorkflowStep`
8. `AgentApp / AgentAppGrant`

### 2.2 运行时主模型原则

禁止把 `RuntimeArtifact` 长期降级回 `ToolMeta` 作为主运行时对象。

后续 runtime 侧必须遵守：

1. `RuntimeArtifact` 是运行时编译产物和发布产物。
2. Registry 面向 `ToolPublicationProvider` 或等价抽象，而不是面向单一 legacy factory。
3. `ArtifactToolFactory` 或等价工厂必须直接接受 `RuntimeArtifact` 作为输入。
4. legacy `ToolMeta` 只能通过 `LegacyToolPublicationProvider` 或等价适配器参与运行时。

### 2.3 兼容层边界原则

以下对象只允许承担兼容职责，不允许继续承载新主能力：

1. `ToolMeta`
2. `assistant_capability_registry`
3. `CapabilityBridgeToolFactory`
4. `CapabilityBridgeTool`
5. `TenantAwareToolRegistry` 的 legacy-only 装载路径
6. `migration` profile 下的旧认证/旧路由兼容接口

允许的修改类型仅限：

1. bugfix
2. 兼容旧前端/旧数据
3. 给新主干做迁移桥接
4. 为下线 legacy 做观测、审计、回收准备

不允许的修改类型包括：

1. 在 legacy 模型上继续叠加新业务语义
2. 把新控制面对象回填成 legacy 主模型后再长期运行
3. 为了省改造成本，继续把新工作流编排压回 `ToolMeta.executionPlan`
4. 把多系统、多鉴权、多主体映射语义重新折叠成单一 `systemCode`

### 2.4 文档与计划约束

后续任何新的实施计划文档，都必须显式回答以下问题：

1. 该实现是否以 `RuntimeArtifact` 为运行时主模型。
2. 该实现是否让 legacy 继续只作为适配层。
3. 若仍触碰 legacy 组件，是否仅为兼容和迁移目的。
4. 该实现完成后，legacy 边界是否更收缩而不是更膨胀。

如果回答不满足以上条件，则该计划视为不符合本决议。

### 2.5 管理权限与接入边界原则

控制面管理能力必须保持在 access-plane / compatibility boundary，不能重新混入 runtime publication 主线。

后续必须遵守：

1. `/api/controlplane/**` 的访问权限必须由显式 permission、显式 admin role 或等价授权模型控制，不能以“登录成功即默认拥有管理权限”作为长期行为。
2. control-plane API 的粗粒度入口校验可以在 `SecurityConfig` / filter chain 层完成，但 `space / app` 级 scope 校验必须在 compatibility-boundary 授权服务中完成，不能把 scope 语义塞进 runtime publication 主线。
3. `migration` profile 下允许使用 `local_user_account + local_user_grant` 承担私有化兼容身份边界，但这只是兼容实现，不是最终企业 IAM 主模型。
4. `agent_app_grant` 只用于 agent app 的 publication / artifact 范围控制，不能回收为 control-plane admin 权限主模型。
5. 控制面 API 应优先暴露 typed request/response DTO，不应把底层 `grant_mode / target_type / constraints_json` 直接泄露给上层调用方。
6. 若后续接入企业 IdP / IAM，应替换 compatibility boundary，而不是把 compatibility grant 继续上推到 artifact/runtime 主干。
7. `local_user_grant` 的 scoped control-plane admin 管理必须通过 typed policy service / controller 暴露，不能要求上层调用方直接拼接或写入原始 grant 行。

---

## 3. 对现有模块的约束解释

### 3.1 `assistant-controlplane`

后续继续按最佳路线推进。

这是新主模型的核心承载模块，后续应继续围绕：

1. 控制面对象补全
2. 发布编译链
3. 审批/风险/审计策略对象化
4. 新控制面到 runtime artifact 的发布接口

### 3.2 `assistant-runtime`

后续以“artifact-native orchestrator”作为唯一目标方向。

允许继续演进的主线是：

1. `RuntimeArtifactCompiler`
2. `RuntimeArtifactCatalogService`
3. `ToolPublicationProvider`
4. `ArtifactToolFactory`
5. `ArtifactCatalogPublicationProvider`
6. `CredentialBroker` 相关新主干

不应继续扩展的方向是：

1. 直接增强 `CapabilityBridgeToolFactory` 作为最终注册中心
2. 继续把 runtime artifact 转回 `ToolMeta` 作为长期运行时桥
3. 用 `systemCode` 重新定义新 runtime 的主身份模型

### 3.3 `assistant-execution`

后续应明确升级为真实 workflow runtime。

执行引擎的未来工作必须围绕：

1. 多 step type 执行器
2. 真实 DAG 调度
3. step 级 connector/auth/profile/binding 解析
4. 审批与恢复
5. execution event contract

### 3.4 `assistant-api`

后续应围绕 access plane / SSE gateway 演进，不再承担主执行语义。

---

## 4. 对已实现工作的处理规则

已实现内容不要求立即推翻，但必须按下面三类处理：

1. `Aligned`：符合最佳路线，可继续作为主干积累。
2. `Transitional`：允许保留，用于兼容或迁移，但禁止继续承载新主能力。
3. `Superseded`：方向已废弃，不得继续推进。

具体分类见：

`docs/plans/2026-03-10-best-route-implementation-audit.md`

---

## 5. 后续实施红线

从本决议生效开始，以下做法视为违反架构主线：

1. 新增任何以 `ToolMeta` 为主控制面输入的功能。
2. 新增任何以“先把 RuntimeArtifact 伪装成 ToolMeta 再运行”为长期方案的功能。
3. 继续围绕 `systemCode` 单维度扩展多系统、多鉴权能力。
4. 在未说明兼容目的的情况下继续增强 `CapabilityBridgeToolFactory` 为主发布入口。
5. 新计划文档未说明与本决议的关系就直接实施。

---

## 6. 后续默认实施顺序

后续默认按以下主干顺序推进：

1. `RuntimeArtifact` publication backbone
2. provider-driven runtime registry
3. artifact-native tool publication
4. legacy publication provider
5. workflow runtime replacement
6. credential broker / auth resolver replacement
7. execution event contract and SSE convergence
8. legacy shrink and retirement

---

## 7. 结语

这份决议的作用不是补充一个“建议”，而是收敛后续实现方向。

从现在开始：

`最佳路线不是参考项，而是后续实现的唯一准绳。`




