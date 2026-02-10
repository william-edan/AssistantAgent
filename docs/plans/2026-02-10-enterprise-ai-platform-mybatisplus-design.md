# 企业 AI 中台（SaaS + AssistantAgent + MyBatis-Plus）执行计划与评估（更新）

日期：2026-02-10

## 1. 目标与约束

目标：

1. 构建 Java 技术栈的企业 AI 中台 SaaS。
2. 客户可通过对话发起操作、查询、分析，并可跨系统执行。
3. 平台要支持多系统接入、用户体系打通、能力治理与审计闭环。
4. 必须避免 AI 调用未注册能力或未注册接口。

约束：

1. 基于 Alibaba 开源 AssistantAgent 落地。
2. 持久层统一 MyBatis-Plus。
3. 表名统一 `assistant_` 前缀。

---

## 2. 本轮重构结论（DAG + 向量 + 分层）

结论：**可行，并已完成核心链路重构。**

本轮从“单接口直调”升级为“分层编排模型”：

1. 能力流程层：`routeConfigJson` 支持 `steps` 与 `nodes+edges(DAG)`。
2. 对话收集层：新增会话槽位快照，缺槽返回 `COLLECTING`，支持多轮补齐。
3. 连接器层：`ConnectorInvoker` 只负责授权后的系统调用，不承载业务流程。
4. 语义召回层：新增能力召回 API，向量用于候选召回，不直接触发执行。

---

## 3. 已落地能力（代码级）

模块：`assistant-agent-start/src/main/java/com/alibaba/assistant/agent/start/saas`

### 3.1 数据模型

新增/调整：

1. `assistant_conversation_session`（会话槽位状态）
2. `assistant_capability_version.tool_binding_json` 改为可空（流程改以 `routeConfigJson` 为主）

### 3.2 核心服务分层

1. 对话层
   - `ActionGatewayService`
   - 行为：幂等、发布态校验、权限校验、槽位收集、执行审计。
2. 槽位层
   - `ConversationSessionService`
   - `SlotCollectionService`
   - 行为：`input + session snapshot` 合并，缺槽返回 `COLLECTING`。
3. 流程层
   - `CapabilityWorkflowService`
   - 行为：支持 `steps` 线性执行与 `nodes+edges` 拓扑执行，支持 `${input.xxx}` 与 `${node.xxx}` 变量绑定。
4. 连接器授权层
   - `ConnectorAuthorizationService`
   - `ConnectorAuthProvider` / `DefaultConnectorAuthProvider`
   - 行为：按 `authType`（SESSION/BEARER/BASIC）生成请求头/Cookie。
5. 连接器调用层
   - `HttpConnectorInvoker`（真实 HTTP）
   - `DefaultMockConnectorInvoker`（测试兜底）

### 3.3 向量召回

新增：

1. `CapabilitySemanticRecallService`
2. `CapabilityRecallController`
3. API：`GET /api/v1/tenant/{tenantId}/capabilities/recall?query=...&topK=...`

说明：

1. 当前使用可替换的默认向量实现（hash embedding）做语义召回。
2. 召回只用于候选能力排序，执行仍经过网关强约束。

---

## 4. 关键需求映射

### 4.1 能力需要提供接口给接口注册

已满足：

1. 连接器接口注册表 `assistant_connector_api`
2. 创建能力版本时强校验：
   - `routeConfigJson.steps[*].apiCode` 或 `routeConfigJson.nodes[*].apiCode`
   - 必须命中接口注册白名单

### 4.2 可能会有多个系统对接

已满足：

1. `ConnectorInvoker` SPI 路由（按 `connectorType`）
2. `ConnectorAuthProvider` SPI 路由（按 `authType`）
3. 支持 Mock/HTTP，后续可平滑扩展 OA/ERP/CRM 专用 invoker

### 4.3 打通助手用户和客户用户体系

已满足：

1. `assistant_user_binding` 维护平台用户与外部用户映射
2. `DELEGATED_USER` 模式强制绑定校验

### 4.4 避免未注册能力/虚拟接口调用

已满足：

1. 能力必须发布态
2. DAG 节点 `apiCode` 必须注册且 ACTIVE
3. 连接器与鉴权必须就绪
4. 执行全量审计落库，含 `request_id` 幂等

---

## 5. 与 AssistantAgent 的集成边界（建议）

推荐职责划分：

1. AssistantAgent：意图理解、能力候选消歧、槽位抽取
2. SaaS Action Gateway：发布态/白名单/权限/会话校验
3. Workflow Engine：DAG 确定性执行

这样可以将“模型理解不确定性”与“系统执行确定性”解耦。

---

## 6. 勾股 OA 请假两步流程（已支持配置化）

能力流程可配置为：

1. 节点1：`/home/leaves/add`
2. 节点2：`/api/check/submit_check`
3. 通过 `${node.leave_add.data.action_id}` 传递 `action_id`

即：流程写在 `routeConfigJson`，不写死在 `ConnectorInvoker`。

---

## 7. 验证结果

执行：

```bash
mvn -pl assistant-agent-start -Dtest=SaasFlowIntegrationTest test
mvn -pl assistant-agent-start test
```

结果：

1. `SaasFlowIntegrationTest`：5/5 通过
2. `assistant-agent-start` 全量测试：7 运行，0 失败，1 跳过

覆盖场景：

1. DAG 两步流程执行成功
2. 多轮槽位收集（COLLECTING -> DONE）
3. 代理用户绑定校验
4. 路由节点未注册接口拦截
5. 语义召回能力候选

真实集成测试入口（按需开启）：

1. `assistant-agent-start/src/test/java/com/alibaba/assistant/agent/start/saas/OfficeLeaveRealIntegrationTest.java`
2. 运行说明见：`docs/plans/2026-02-10-office-leave-real-integration-test.md`

---

## 8. 下一步计划（建议 2~4 周）

1. 接入真实向量模型
   - 对接 DashScope Embedding 或通义向量模型，替换默认 hash embedding
2. 规则增强
   - DAG 条件边（if/else）、重试策略、节点超时与补偿策略
3. 安全增强
   - 节点级权限、字段脱敏、审计检索 API
4. Assistant 深度联动
   - 将召回候选能力与槽位 schema 注入 Prompt，形成稳定多轮执行闭环
