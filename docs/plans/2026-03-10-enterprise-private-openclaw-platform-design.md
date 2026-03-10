# 企业私有化 OpenClaw 平台最佳路线设计

> **实施约束：** 自 2026-03-10 起，后续所有实现必须遵循 docs/plans/2026-03-10-best-route-implementation-governance.md，并以 docs/plans/2026-03-10-best-route-implementation-audit.md 作为已落地实现的保留/限制/废弃依据。


> 日期：2026-03-10
> 适用项目：`D:/devfive/AssistantAgent`
> 当前运行模式：`migration`
> 目标：将当前工程演进为“企业可私有化部署的 AI Gateway / Control Plane”，让企业以标准化方式接入内部系统，实现系统 AI 化，而不是让 agent 直接持有业务调用逻辑和认证逻辑。

---

## 1. 执行摘要

当前架构已经具备企业 AI 平台的几个关键雏形：

1. 已有统一认证入口与本地会话能力，见 `assistant-api/.../MigrationAuthService.java` 与 `assistant-api/.../SystemAuthProxyController.java`。
2. 已有统一工具注册与运行时装配能力，见 `assistant-controlplane/.../ToolMetaService.java`、`assistant-runtime/.../CapabilityBridgeToolFactory.java`。
3. 已有身份注入、预算、白名单、确认、租户隔离等治理拦截器，见 `assistant-runtime/.../IdentityEnricherToolInterceptor.java`、`PolicyGuardToolInterceptor.java`、`HumanInTheLoopToolInterceptor.java`。
4. 已有 OpenAPI 与 MCP 动态工具工厂，见 `assistant-agent-extensions/.../HttpDynamicToolFactory.java` 与 `McpDynamicToolFactory.java`。
5. Chat 入口已经支持 SSE，见 `assistant-api/.../ChatController.java`。

因此，当前系统不是“没有平台能力”，而是“平台能力已经存在，但产品抽象还不够稳定，控制面与执行面还没有完全拉开”。

本设计的核心结论如下：

1. `migration` 可以继续作为兼容层存在，但不应成为最终产品架构的中心。
2. 最佳路线不是让 agent 自己写代码去认证和调用企业系统。
3. 最佳路线是建设一个私有化、控制面驱动、协议优先、审计优先、机器身份优先的 `Enterprise AI Gateway`。
4. 当前架构的灵活性较强，但 agent 编排能力仍然停留在“受控工具编排”，还不是“企业级流程编排”。
5. 一个企业存在多个系统、多个认证模型、多个主体映射是常态，平台必须将其建模为一等能力。
6. 后续控制面设计必须严格区分“设计态对象”和“运行态解析结果”，否则认证解析和工作流治理会再次退化为大 capability 配置。

建议评分如下：

1. 灵活性：`7/10`
2. Agent 编排性：`5/10`
3. 企业级控制面成熟度：`4/10`

---

## 2. 结论先行

### 2.1 关于“让 agent 自己写代码发起认证和调用”

不建议将其作为平台默认模式。

原因不是“agent 做不到”，而是这种模式不适合作为企业私有化平台的主路径：

1. 认证逻辑会分散到 agent 生成代码中，导致审计、权限边界、轮转、熔断、重试、回放都失控。
2. 凭据、令牌、Cookie、Session 等安全对象会落入模型上下文或临时代码路径，风险不可接受。
3. 运行结果缺乏稳定事件模型，难以统一输出给前端、审批系统、审计系统和运维系统。
4. 不同企业系统的接入会被“临时代码成功调用”掩盖，最终无法沉淀为可复制产品。

可接受的做法是：

1. 平台负责认证、连接、执行、治理、审计。
2. Agent 负责意图理解、动作规划、参数补全、执行协商。
3. 只有标准连接器无法覆盖的长尾场景，才允许 agent 生成适配代码，而且必须在受控沙箱中执行，并沉淀为版本化 Connector 资产。

### 2.2 关于当前架构的本质判断

当前架构更接近下面这个定位：

`受控工具网关 + 槽位驱动依赖调用 + 基础审批治理 + SSE 聊天输出`

它还不是下面这个定位：

`企业级 AI Gateway / Control Plane + 真正工作流编排引擎 + 统一事件总线 + 可管理连接器市场`

这意味着：

1. 当前基础是对的，方向不是错的。
2. 但产品抽象层需要重构，不能继续围绕 `ToolMeta + executionPlan + migration auth` 直接扩展。
3. 不能再假设“一个企业一个系统码、一套认证逻辑、一条 capability 记录就能表达业务能力”。

---

## 3. 设计目标

面向私有化部署，本平台的目标不是“做一个可以调用接口的 agent”，而是：

1. 让企业通过标准方式接入内部系统、流程、审批、知识和模型。
2. 让 agent 在受控边界内调用这些能力，而不是自由持有系统调用权。
3. 让所有动作都具备明确的身份边界、策略边界、审批边界和审计边界。
4. 让前端、审批中心、运维中心都消费同一套执行事件，而不是各自解析内部状态。
5. 让一个企业内的多个系统、多个认证模型、多个主体映射能够统一被平台解析和编排。
6. 让控制面定义表达“可选认证集合、绑定策略、风险规则、工作流编排”，而运行时负责解析“本次执行最终使用了哪种认证和哪种主体”。

最终产品形态应是：

`Private Enterprise AI Gateway + Connector Control Plane + Agent Orchestrator + Workflow Runtime + Audit Plane`

---

## 4. 现状评估

### 4.1 当前优势

#### A. 接入方式已经多样化

当前系统不是单一路径。

1. 数据库元数据工具：`assistant-controlplane/.../ToolMetaService.java`
2. OpenAPI 动态工具：`assistant-agent-extensions/.../HttpDynamicToolFactory.java`
3. MCP 动态工具：`assistant-agent-extensions/.../McpDynamicToolFactory.java`

这说明系统已经具备“统一能力注册入口”的基础。

#### B. 身份和执行已经开始解耦

当前链路已经不是前端拼 token 后直接调接口：

1. 本地登录和会话：`assistant-api/.../MigrationAuthService.java`
2. 身份注入：`assistant-runtime/.../IdentityEnricherToolInterceptor.java`
3. 外部系统 token 获取：`assistant-runtime/.../TokenExchangeTokenBroker.java`
4. HTTP 执行：`assistant-execution/.../HttpStepExecutor.java`

这说明系统已经具备私有化平台最重要的方向之一：认证与执行分层。

#### C. 治理能力已有基础

当前已具备以下控制点：

1. 白名单
2. 预算限制
3. 租户隔离
4. 风险确认
5. Human-in-the-loop

关键实现位于：

1. `assistant-runtime/.../PolicyGuardToolInterceptor.java`
2. `assistant-runtime/.../HumanInTheLoopToolInterceptor.java`

#### D. 编排雏形已经存在

1. `executionPlan -> FlowDefinition`：`assistant-execution/.../FlowDefinitionConverter.java`
2. Flow 执行器：`assistant-execution/.../DAGFlowExecutor.java`
3. 依赖解析：`assistant-runtime/.../DependencyResolver.java`
4. 依赖调用执行器：`assistant-runtime/.../ToolExecutor.java`
5. 字段映射回槽位：`assistant-runtime/.../FieldMappingProcessor.java`
6. 主链路接入点：`assistant-runtime/.../SlotCollectTool.java`

这意味着当前系统已经不是“纯单工具调用”，而是具备浅层依赖编排能力。

### 4.2 当前限制

#### A. `ToolMeta` 过载

`assistant-controlplane/.../ToolMeta.java` 同时承载：

1. 接口地址
2. 参数 schema
3. 执行计划
4. 风险等级
5. 交互策略
6. 是否鉴权
7. 是否确认

它更像“所有信息都堆在一个表里”，而不是可产品化的领域模型。

#### B. 编排能力名义上比实际上强

虽然 `StepType` 包含 `HTTP / CONDITION / TRANSFORM / DELAY / DATA_AGENT`，见 `assistant-execution/.../StepType.java`，但 `DAGFlowExecutor` 当前实际只执行 `HTTP`。

这意味着：

1. 运行时不是真正的 DAG runtime
2. 没有并行
3. 没有分支
4. 没有补偿
5. 没有工作流级事件

#### C. 内部依赖调用的治理边界还不够严

`assistant-runtime/.../PolicyGuardToolInterceptor.java` 对 `internalDependencyCall` 有一定豁免，这对实现依赖调用是方便的，但如果作为平台长期机制，会造成：

1. 部分内部调用绕过正常策略边界
2. 审批语义与实际执行语义可能不一致
3. 审计难区分“平台自动补全依赖”与“业务系统主动执行动作”

#### D. 流式只到 chat，没有贯穿到 execution

`assistant-api/.../ChatController.java` 已经基于 SSE 输出聊天事件，但工具执行和流程执行还没有统一的事件流协议。

当前缺少：

1. `execution.started`
2. `identity.resolved`
3. `approval.required`
4. `step.started`
5. `step.completed`
6. `step.failed`
7. `execution.completed`

#### E. 对多系统、多鉴权、多主体映射的建模还不够

当前主模型仍偏向：

`匹配到哪个 capability -> capability 带哪个 systemCode -> 用哪套认证`

这个思路对单系统简单动作还可接受，但对企业场景不够：

1. 一个企业通常有多个系统
2. 同一系统可能同时存在多种认证方式
3. 同一个用户在不同系统中的主体身份可能不同
4. 查询和写操作经常使用不同的凭据和权限边界

---

## 5. 最佳路线：目标产品定义

最佳路线不是“继续把 migration 做大”，而是重新定义产品核心：

`企业私有化 AI Gateway 平台`

平台必须具备五个基本属性：

1. 私有化优先
2. 控制面优先
3. 机器身份优先
4. 协议优先
5. 审计优先

对外产品承诺应是：

1. 企业只需接入系统连接器和动作规范，即可把内部系统开放给 agent 使用。
2. 企业不需要把认证逻辑和调用逻辑交给模型临时拼接。
3. 高风险操作必须有明确策略、审批和回放能力。
4. 平台支持本地模型、企业模型网关和外部模型，但模型不是信任边界。
5. 平台能够显式表达“一个企业多个系统、一个系统多个认证、一个用户多个主体映射”的真实组织状态。

---

## 6. 目标架构

### 6.1 总体分层

#### 1. Access Plane

负责：

1. 用户入口
2. SSE / WebSocket / HTTP API
3. 前端会话接入
4. 身份断言传递

当前 `assistant-api` 可以保留为这个平面的兼容入口，但未来需要统一为可信身份上下文接入层，而不是继续依赖前端传 `userId/systemCode/assistantUid`。

#### 2. Auth Plane

负责：

1. 对接企业 IdP
2. 机器身份认证
3. CredentialBroker / Token Broker
4. Secret / Credential / Vault 管理
5. Token audience 校验
6. Session 与身份绑定

当前 `MigrationAuthService` 与 `TokenExchangeTokenBroker` 只能视为过渡实现。

最终 Auth Plane 应同时支持：

1. OAuth 2.1 / OIDC
2. OAuth Client Credentials
3. JWT Assertion
4. API Key
5. Cookie / Session 登录
6. mTLS

#### 3. Connector Control Plane

负责：

1. Connector 注册
2. Action 模型管理
3. AuthProfile 管理
4. 风险策略与审批策略
5. 版本发布与回滚
6. 测试与仿真

这是当前最缺的产品层。

#### 4. Orchestration Plane

负责：

1. 意图理解
2. 动作选择
3. 依赖推断
4. 参数补全
5. 执行协商
6. 人机协同决策

#### 5. Workflow Runtime

负责：

1. 真正 DAG 执行
2. 条件分支
3. 并行执行
4. Join
5. Retry
6. Timeout
7. Compensation
8. Resume

#### 6. Event & Audit Plane

负责：

1. 标准执行事件
2. 审批事件
3. 风险事件
4. Trace / Span
5. 回放
6. 运维观测

它不应只是日志，而应是产品的一等能力。

### 6.2 私有化部署拓扑与术语约定

私有化场景下，建议采用：

`每个企业一套独立部署单元`

而不是先按公有云多租户模型设计。

推荐拓扑：

1. `Gateway API`
2. `Connector Control Plane`
3. `Workflow Runtime`
4. `Policy Engine`
5. `Audit Store`
6. `Secret Manager / Vault`
7. `Redis / MQ / Database`
8. `Model Gateway`

部署原则：

1. 默认内网部署
2. 默认回环或内网访问
3. 默认出口白名单
4. 默认按企业环境分离：`dev / test / prod`
5. 默认凭据不出密管系统

术语统一如下：

1. `enterprise`：客户企业实体，是采购和部署边界。
2. `deployment`：该企业的一套私有化平台实例。
3. `environment`：`dev / test / prod` 等环境。
4. `space`：企业实例内部的逻辑业务域或隔离空间，例如事业部、子品牌、业务线。
5. `tenantId`：仅作为兼容当前仓库和旧表结构的逻辑空间标识使用，不再表示“跨企业 SaaS 租户”。

新控制面设计建议优先使用 `spaceId` 或 `domainId`，仅在兼容层继续保留 `tenantId`。

### 6.3 用户登录后如何判定调用哪个系统的认证

平台必须区分两个问题：

1. 用户登录 AI 系统时，确认的是“你是谁”。
2. 执行某个动作时，确认的是“这一步该用哪个系统、哪个认证、哪个主体映射”。

因此：

1. 登录 AI 系统不会直接决定调用哪个系统认证。
2. 目标动作或工作流步骤才决定使用哪个 Connector。
3. 每个步骤再根据 Connector 和控制面定义的候选集选择允许的 AuthProfile。
4. AuthProfile 再结合 PrincipalBinding 和策略决定使用哪种目标主体身份。

推荐判定链路：

1. 用户登录平台，得到平台身份：`enterprise / deployment / environment / spaceId / userId / department / roles`
2. Planner 识别目标 `Action` 或 `Workflow`
3. 每个步骤解析目标 `Connector`
4. 每个步骤读取该步骤的 `allowedAuthProfiles`
5. 平台根据 `PrincipalBinding` 解析用户在目标系统中的对应主体
6. `CredentialBroker` 依据策略选出一个 `resolvedAuthProfile` 和一个 `resolvedPrincipalBinding`
7. `CredentialBroker` 返回本步骤的 `CredentialLease`
8. 步骤执行后只回传结构化业务结果，不把 token 共享给其他系统

这意味着多系统组合调用应遵守：

`共享业务上下文，不共享 token`

### 6.4 设计态与运行态必须分离

为了避免认证解析逻辑再次退化成“在 Action 上写死一个 authProfileRef”，控制面必须区分：

1. 设计态：定义允许使用哪些认证、允许哪些主体映射策略。
2. 运行态：记录本次执行最终解析成了哪一个认证和哪一个主体映射。

推荐最小区分如下：

1. `ActionSpec.allowedAuthProfiles`
2. `WorkflowStep.allowedAuthProfiles`
3. `ActionSpec.bindingStrategies`
4. `WorkflowStep.bindingStrategies`
5. `ExecutionStep.resolvedAuthProfile`
6. `ExecutionStep.resolvedPrincipalBinding`
7. `CredentialLease`

### 6.5 多系统组合调用原则

一个企业多个系统时，平台必须支持：

1. 同一工作流中跨多个 Connector 执行
2. 同一个用户在不同系统使用不同主体映射
3. 同一系统内查询和写入步骤使用不同认证方式
4. 某一步因缺授权、缺绑定或缺审批而暂停，而不是整段逻辑退化为 agent 自由写代码

推荐将跨系统调用建模为 Workflow，而不是将多个系统请求直接拼进一条 capability 记录中。

---

## 7. 目标领域模型

当前不应继续以 `ToolMeta` 为中心扩展，而应拆分为下面这些核心对象。

### 7.1 Connector

表示一个企业系统的连接器。

字段示例：

1. `connectorId`
2. `systemCode`
3. `displayName`
4. `environment`
5. `networkZone`
6. `protocolType`：`openapi / mcp / http / sdk / sql`
7. `baseUrl`
8. `status`

### 7.2 AuthProfile

表示该 Connector 的一种认证方式，而不是整个系统唯一认证。

字段示例：

1. `authProfileId`
2. `connectorId`
3. `authType`
4. `tokenEndpoint`
5. `clientIdRef`
6. `clientSecretRef`
7. `tokenHeaderName`
8. `tokenHeaderPrefix`
9. `audience`
10. `scopes`
11. `refreshPolicy`
12. `usagePolicy`：`read_only / mutate / approval / admin`

一个 Connector 允许存在多个 AuthProfile，例如：

1. `oa_catalog_read`
2. `oa_user_mutation`
3. `oa_approval_submit`

### 7.3 PrincipalBinding

表示平台主体与目标系统主体之间的映射关系。

字段示例：

1. `bindingId`
2. `spaceId`
3. `platformPrincipalId`
4. `connectorId`
5. `targetPrincipalType`：`user / service_account / bot / delegated_user`
6. `targetPrincipalId`
7. `bindingStatus`
8. `scopeConstraints`

用途：

1. 一个用户在 OA 中可能是个人账号
2. 在 ERP 中可能没有个人账号，只能走部门机器人账号
3. 在财务系统里可能需要走受委托身份

### 7.4 CredentialBroker 与 CredentialLease

`CredentialBroker` 是运行时认证解析中枢。

输入至少包括：

1. `connectorId`
2. `allowedAuthProfiles`
3. `platformPrincipal`
4. `bindingStrategies`
5. `requiredScopes`
6. `operationClass`
7. `executionContext`

输出为 `CredentialLease`，至少包括：

1. `resolvedAuthProfile`
2. `resolvedPrincipalBinding`
3. `credentialType`：`access_token / api_key / session / signed_request`
4. `leaseHandle` 或 `tokenHandle`
5. `expiresAt`
6. `audience`
7. `connectorId`

规则：

1. 原始密钥和长期凭据不能暴露给模型。
2. Lease 默认是步骤级别，而不是会话级共享。
3. 不允许跨 Connector 复用 Lease。

### 7.5 ReferenceResolver

表示只读引用数据查询能力，不直接作为高层业务动作暴露。

典型用途：

1. 字典查询
2. 审批流查询
3. 用户目录查询
4. 上下文预取
5. 外部引用数据补全

字段示例：

1. `resolverCode`
2. `connectorId`
3. `allowedAuthProfiles`
4. `inputSchema`
5. `outputSchema`
6. `cachePolicy`
7. `stalenessPolicy`
8. `visibility`

### 7.6 BusinessQueryAction

表示对业务对象的显式查询动作，可以面向用户暴露。

典型用途：

1. 查询我的请假余额
2. 查询审批进度
3. 查询订单状态
4. 查询客户详情

字段示例：

1. `queryActionCode`
2. `connectorId`
3. `allowedAuthProfiles`
4. `bindingStrategies`
5. `inputSchema`
6. `outputSchema`
7. `riskLevel`
8. `resultVisibilityPolicy`

原则：

1. `BusinessQueryAction` 是用户可感知的业务查询。
2. `ReferenceResolver` 是工作流或表单的引用数据补全器。
3. 两者不能再混成一个泛化的 `query`。

### 7.7 PreconditionCheck

表示工作流内部的前置校验节点，不直接暴露给用户。

典型用途：

1. 检查余额是否足够
2. 检查流程是否可提交
3. 检查状态是否允许变更

字段示例：

1. `checkCode`
2. `connectorId`
3. `allowedAuthProfiles`
4. `bindingStrategies`
5. `checkExpression`
6. `failurePolicy`

### 7.8 ActionSpec

表示一个原子业务写动作，而不是一个松散工具。

字段示例：

1. `actionCode`
2. `connectorId`
3. `allowedAuthProfiles`
4. `defaultAuthProfile`
5. `bindingStrategies`
6. `inputSchema`
7. `outputSchema`
8. `idempotencyPolicy`
9. `riskLevel`
10. `approvalPolicyId`
11. `observabilityProfile`
12. `sideEffectLevel`

原则：

1. 一个 Action 只代表一个原子业务动作。
2. 不应在一个 Action 里同时塞入查询器、表单交互和多步写操作。
3. `allowedAuthProfiles` 是设计态候选集合，不是运行态最终结果。

### 7.9 InteractionSpec

表示表单交互、槽位收集和确认规则。

字段示例：

1. `interactionId`
2. `slotSchema`
3. `askStrategy`
4. `autoFillRules`
5. `summaryLayout`
6. `confirmationPolicy`
7. `editPolicy`

原则：

1. Interaction 负责“怎么问、怎么补、怎么确认”。
2. 不负责底层系统调用和认证。

### 7.10 PolicyPack

表示平台治理规则集合。

内容包括：

1. 谁可以调用
2. 什么条件下必须确认
3. 什么条件下必须审批
4. 哪些字段必须脱敏
5. 哪些动作禁止自动执行

### 7.11 WorkflowSpec

表示多动作编排规范。

它只在需要多步流程时出现，不应是所有动作的默认表达。

每一步应显式声明：

1. `stepId`
2. `stepType`：`reference_resolve / business_query / precondition_check / action / approval_gate / transform`
3. `connectorId`
4. `targetRef`：引用 `resolverCode / queryActionCode / checkCode / actionCode`
5. `allowedAuthProfiles`
6. `bindingStrategies`
7. `inputMapping`
8. `outputMapping`
9. `dependsOn`
10. `condition`
11. `joinPolicy`
12. `retryPolicy`
13. `timeoutPolicy`
14. `approvalGate`
15. `compensationAction`
16. `resumePolicy`

另外必须定义 workflow 级规则：

1. `riskAggregationPolicy`
2. `approvalAggregationPolicy`
3. `failurePolicy`
4. `auditPolicy`

默认规则建议：

1. workflow 风险等级取子步骤最大值。
2. workflow 审批门禁取最严格策略。
3. 任一步骤的高风险写动作不能被前置只读查询掩盖。

### 7.12 AgentApp

表示一个面向用户的 agent 应用。

字段示例：

1. `agentAppId`
2. `displayName`
3. `allowedActions`
4. `promptPolicy`
5. `memoryPolicy`
6. `approvalStrategy`

### 7.13 ExecutionRun

表示一次真实执行。

必须可追踪：

1. 谁触发
2. 哪个 agent 触发
3. 使用了哪些 Connector
4. 每一步使用了哪个 `resolvedAuthProfile`
5. 每一步使用了哪个 `resolvedPrincipalBinding`
6. 每一步拿到了哪个 `CredentialLease`
7. 中间状态和结果如何
8. 是否触发审批
9. 最终输出是什么

---

## 8. 协议与接入策略

### 8.1 接入优先级

最佳路线下，平台必须明确接入优先级：

1. `OpenAPI 导入`
2. `MCP 接入`
3. `HTTP 低代码动作向导`
4. `SDK / Code Adapter`

只有第四类才允许进入代码适配器路径。

### 8.2 不推荐的默认路径

以下路径不应作为产品默认方案：

1. 让业务方直接写 `executionPlan`
2. 让业务方直接编辑 `ToolMeta` 原始 JSON
3. 让 agent 在对话中直接生成登录代码和接口调用代码
4. 让前端或调用方传入可信身份字段作为最终身份来源

### 8.3 MCP 与 OpenClaw 思路的吸收

参考 OpenClaw 与 MCP 的最佳实践，平台应吸收以下思想：

1. Gateway 是单一执行入口，而不是多个客户端各自持有执行逻辑。
2. 协议层必须清晰，能力以标准接口暴露，而不是以内嵌代码片段暴露。
3. 执行必须是事件化和流式的，而不是任务完成后一次性给出总结果。
4. 认证必须与工具能力解耦，token 不应透传给模型。
5. 企业场景优先机器到机器身份和集中式 IdP，而不是人工登录凭据复用。

### 8.4 多系统、多鉴权、多主体绑定原则

平台必须支持以下现实模型：

1. 一个企业有多个系统
2. 一个系统有多个 AuthProfile
3. 一个用户在不同系统中对应不同主体
4. 同一工作流的不同步骤使用不同认证
5. 同一系统里的查询与写操作使用不同权限边界

因此，认证解析的最小单元必须是：

`Workflow Step / Action Step`

而不是：

`整个企业` 或 `整个会话` 或 `整条 capability`

### 8.5 查询、写操作、交互、工作流必须拆层

像“请假审批”这类场景，平台不应继续把以下内容塞进一条 capability 记录：

1. 字典查询
2. 审批流查询
3. 用户目录查询
4. 请假创建
5. 审批提交
6. 槽位收集
7. 参数确认
8. 两步工作流编排

最佳模型应拆成：

1. `ReferenceResolver`
2. `BusinessQueryAction`
3. `PreconditionCheck`
4. `ActionSpec`
5. `InteractionSpec`
6. `WorkflowSpec`

### 8.6 `leave_application` 示例的推荐拆法

对于当前 capability 定义，推荐拆解为：

1. Connector：`gougu_oa`
2. AuthProfile：
   - `oa_catalog_read`
   - `oa_user_leave_write`
   - `oa_approval_submit`
3. ReferenceResolver：
   - `leave_type_catalog`
   - `approval_flow_catalog`
   - `org_user_directory`
4. PreconditionCheck：
   - `leave_apply_precheck`
5. ActionSpec：
   - `oa.leave.create`
   - `oa.approval.submit`
6. InteractionSpec：
   - `oa.leave.apply.form`
7. WorkflowSpec：
   - `oa.leave.apply`

其中特别要注意：

1. 查询器是只读能力，应支持缓存和更宽松的认证策略。
2. `oa.leave.create` 是写动作，风险级别和审批规则必须独立定义。
3. `oa.approval.submit` 是第二个写动作，不能被隐藏在 `response_config` 或 `flow_steps` 的附属字段里。
4. 整个“请假审批”是 Workflow，不是单个 Action。
5. 如果未来出现“查询我的请假余额”这类需求，应定义为 `BusinessQueryAction`，而不是复用 `ReferenceResolver`。

---

## 9. 最佳实践约束

### 9.1 Private-by-default

平台默认应支持：

1. 本地模型
2. 企业模型网关
3. 内网知识与系统
4. 内网或回环通信

参考：

1. [OpenClaw Docs](https://openclaw.im/docs)

### 9.2 Machine Identity First

对后台系统调用，优先使用机器身份，不优先使用模拟用户密码登录。

推荐顺序：

1. OAuth Client Credentials
2. JWT Assertion
3. API Key + Vault
4. 受控 Session / Cookie

参考：

1. [MCP OAuth Client Credentials Extension](https://modelcontextprotocol.io/extensions/auth/oauth-client-credentials)

### 9.3 Token Audience Validation

平台必须验证 token 是为本平台或指定资源签发的，不能接受“别的资源签发的 token 拿来复用”。

平台必须避免：

1. Token passthrough
2. 代理服务接受非本资源 audience token
3. 将用户 token 无边界透传给下游服务

参考：

1. [MCP Authorization 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)
2. [MCP Security Best Practices](https://modelcontextprotocol.io/specification/draft/basic/security_best_practices)

### 9.4 Session 不能成为认证本身

Session 只能承载会话状态，不能替代认证边界。

平台必须保证：

1. 所有入站请求都重新做鉴权
2. Session ID 不作为唯一信任凭据
3. Session 必须与用户或主体绑定

### 9.5 Execution Event First

执行必须是事件优先，而不是日志优先。

最少需要以下事件：

1. `execution.created`
2. `execution.started`
3. `identity.resolved`
4. `approval.required`
5. `approval.granted`
6. `step.started`
7. `step.output`
8. `step.completed`
9. `step.failed`
10. `execution.completed`

---

## 10. 对当前仓库的最佳路线改造建议

以下路线不考虑“最小变更”，而是从最终正确性出发。

### 10.1 将 `migration` 降级为兼容边界

`migration` 的职责应重新定义为：

1. 兼容现有前端和旧接口
2. 兼容旧数据库结构和旧调用协议
3. 作为新平台的过渡入口

不应再将其定义为最终产品内核。

### 10.2 用 Connector 模型替代 `ToolMeta` 中心模型

保留 `ToolMeta` 作为兼容层或运行时编译产物，但产品控制面不再以它为主对象。

目标是：

1. `ToolMeta` 退化为 Runtime Artifact
2. `Connector / AuthProfile / PrincipalBinding / CredentialBroker / ReferenceResolver / BusinessQueryAction / PreconditionCheck / ActionSpec / InteractionSpec / PolicyPack / WorkflowSpec` 成为控制面一等对象
3. `systemCode` 退化为 Connector 标识的一部分，而不是认证解析的唯一维度

### 10.3 将 OpenAPI / MCP 提升为一等接入方式

当前 OpenAPI 与 MCP 只是动态工具工厂，未来应变成：

1. Connector 导入入口
2. 动作规范生成入口
3. 仿真测试入口
4. 发布入口

### 10.4 将 `assistant-execution` 升级为真实 Workflow Runtime

目标能力：

1. 真实 DAG 调度
2. 条件分支
3. 并行执行
4. Join 与 Barrier
5. Retry / Timeout / Backoff
6. Compensation
7. Workflow 级事件流
8. 持久化 Resume
9. 每一步独立解析 Connector、AuthProfile 与 PrincipalBinding
10. 运行态记录 `resolvedAuthProfile / resolvedPrincipalBinding / CredentialLease`

### 10.5 将 `assistant-runtime` 明确拆成 Orchestrator 内核

建议拆分为：

1. Intent Layer
2. Planning Layer
3. Policy Decision Layer
4. Tool Invocation Layer
5. Conversation State Layer

避免继续在 `SlotCollectTool`、`AssistantFastIntentHook` 中堆积过多跨层逻辑。

### 10.6 建立统一 Execution Event Contract

`assistant-api/.../ChatController.java` 目前已支持 SSE，但未来 SSE 不应只承载聊天增量文本。

统一事件契约需要覆盖：

1. 文本增量
2. 工具调用状态
3. 审批请求
4. 风险提示
5. 工作流步骤状态
6. 最终产出
7. 认证解析状态

### 10.7 建立企业私有化部署标准包

最终产品必须能以标准方式交付：

1. Docker Compose
2. Helm Chart
3. 环境参数模板
4. Secret/Vault 对接模板
5. Connector 示例包
6. 健康检查与诊断工具

---

## 11. 模块目标职责重组

| 当前模块 | 当前主要角色 | 目标角色 |
|---|---|---|
| `assistant-api` | 兼容入口、聊天接口 | Access Plane / SSE Gateway / API Gateway |
| `assistant-runtime` | Agent 装配与拦截 | Orchestration Plane |
| `assistant-execution` | 简化 Flow 执行 | Workflow Runtime |
| `assistant-controlplane` | ToolMeta CRUD | Connector Control Plane |
| `assistant-agent-extensions` | 动态工具工厂 | Connector Runtime Adapters |
| `assistant-agent-common` | 常量与抽象 | 统一协议与领域模型 |
| `assistant-agent-start` | Demo 启动工程 | 私有化参考发行版 |

---

## 12. 最终产品能力边界

平台应该默认支持：

1. 查询类动作自动执行
2. 低风险写动作二次确认
3. 高风险写动作审批
4. 多系统串联编排
5. 流式执行反馈
6. 全链路审计
7. OpenAPI / MCP 标准接入
8. 机器身份与企业身份统一管理
9. 同一工作流中多系统、多认证、多主体映射解析

平台不应默认支持：

1. 模型自由持有明文凭据
2. 模型在每次对话中现场写业务调用代码并直接执行
3. 通过前端传入的身份参数作为最终身份
4. 依赖内部隐式规则而不是显式策略执行高风险动作
5. 通过一条 capability 记录同时表达查询、写入、交互和多步工作流

---

## 13. 验收标准

当平台演进到目标态时，应满足：

1. 企业接入一个新系统时，不需要直接改 agent 代码。
2. 一个新系统至少可通过 OpenAPI、MCP 或低代码 HTTP 向导三者之一完成接入。
3. 平台可以明确区分 `Connector`、`AuthProfile`、`PrincipalBinding`、`CredentialBroker`、`ReferenceResolver`、`BusinessQueryAction`、`PreconditionCheck`、`Action`、`Interaction`、`Workflow`、`Policy`、`Approval`。
4. 所有写操作都能在审计中追溯到：谁、何时、通过哪个 agent、在何种授权条件下、执行了哪个动作。
5. 前端能实时消费执行过程，而不是等待任务整体结束。
6. 私有化部署不依赖外部云控制面即可独立运行。
7. 同一个企业中的多个系统可以在一个工作流中组合调用，并且每一步使用自己的认证和主体映射。
8. 设计态对象和运行态解析结果在审计中可区分，例如 `allowedAuthProfiles` 与 `resolvedAuthProfile`。

---

## 14. 最终结论

当前系统的方向没有问题，但产品抽象层次还不够。

更准确地说：

1. 现在已经有了企业 AI 平台的技术底座。
2. 但还没有形成企业私有化 OpenClaw 式平台的稳定控制面。
3. 当前最值钱的部分不是 `migration` 兼容接口本身，而是已经出现的几个正确方向：
   - 认证与执行解耦
   - OpenAPI / MCP 动态接入
   - 风险确认与人机协同
   - SSE 流式输出
   - 依赖工具执行的初步框架
4. 下一步最关键的不是继续把 capability 配置做得更复杂，而是把它们拆成 Connector、AuthProfile、PrincipalBinding、CredentialBroker、ReferenceResolver、BusinessQueryAction、PreconditionCheck、Action、Interaction、Workflow 这些稳定对象。

因此，最佳路线不是继续打磨“agent 自己写代码调用企业系统”的能力，也不是继续堆叠“一条 capability 同时做查询、写入、审批、交互”的配置模型，而是把这些已有基础提升为：

`企业私有化 AI Gateway + Connector Control Plane + Workflow Runtime + Event/Audit Plane`

这是最符合企业采购、私有化交付、安全治理和长期产品化的路线。

