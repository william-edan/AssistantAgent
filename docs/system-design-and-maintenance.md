# Assistant Agent 系统设计说明

## 1. 文档目的

这份文档面向后续维护人员，目标是回答四个问题：

- 系统的唯一主模型是什么
- 一次对话如何从登录走到表单、执行、结果
- 长任务、聊天记录、任务中心、站内信分别如何落库
- 出问题时应该先看哪一层

当前版本已经完全收敛到 canonical-only 架构：

- 控制面唯一动作定义是 `tool_meta`
- 查询型 resolver 工具与用户可见业务工具分层发布
- 前端协议统一输出 `STAGE / FORM_STATE / TASK_STATE / EXECUTION_PROGRESS / RESULT / ERROR`
- 聊天、任务、通知全部通过事件投影生成读侧

## 2. 设计原则

### 2.1 ToolMeta 是唯一动作定义

系统不再维护多套动作定义模型。运行时只认 `tool_meta`，关键治理维度如下：

- `toolType = QUERY | ACTION | WORKFLOW | AGENT_TASK`
- `visibility = USER | PLANNER | INTERNAL`
- `invocationPolicy = DIRECT | COMPOSABLE | DEPENDENCY_ONLY`
- `executionMode = SYNC | ASYNC`

### 2.2 查询与执行分层

查询型 resolver 工具和用户业务工具必须分层：

- 用户可直接发起的业务能力：`USER + DIRECT`
- 可复用查询能力：`QUERY`
- 只给槽位补全和依赖解析使用的内部工具：`INTERNAL + DEPENDENCY_ONLY`

这意味着：

- `gougu_oa.leave_application` 会暴露给对话层
- `gougu_oa.approver_candidates` 不会暴露给最终用户
- 前端不会看到 resolver 自己的任务卡和执行过程

### 2.3 API 层只负责稳定协议

模块职责已经明确：

- `assistant-api`：登录、聊天 SSE、线程/消息/任务/通知读接口、协议适配
- `assistant-runtime`：Agent 编排、表单收集、确认、执行、长任务投影
- `assistant-slot`：槽位补全、候选项加载、默认值和计算字段
- `assistant-execution`：HTTP 执行器与持久化实体
- `assistant-controlplane`：连接器、认证配置、ToolMeta 治理
- `assistant-infra`：Flyway、MySQL 表结构、中文表注释和种子数据

## 3. 总体架构

```mermaid
flowchart TD
  User[前端 / 用户] --> Login[/system/auth/login]
  User --> Run[/api/chat/run_sse]
  User --> Resume[/api/chat/resume_sse]
  User --> Events[/api/chat/threads/{threadId}/events_sse]
  User --> Threads[/api/chat/threads]
  User --> State[/api/chat/threads/{threadId}/state]
  User --> Messages[/api/chat/threads/{threadId}/messages]
  User --> Tasks[/api/chat/tasks]
  User --> Notices[/api/chat/notifications]

  Login --> Auth[MigrationAuthService]
  Run --> Chat[ChatController]
  Resume --> Chat
  Chat --> Protocol[V3ProtocolAdapter]
  Chat --> Planner[Planner Agent]

  Planner --> Collect[slot_collect]
  Planner --> Confirm[slot_confirm]
  Planner --> Execute[artifact_execute]

  Collect --> Slot[assistant-slot]
  Confirm --> Slot
  Execute --> RuntimeExec[ArtifactRuntimeExecutor]
  RuntimeExec --> HttpExec[HttpStepExecutor]

  Slot --> Resolver[ToolBackedSlotOptionResolver]
  Resolver --> ToolMeta[(tool_meta)]
  Resolver --> HttpExec

  Execute --> Broker[TokenExchangeTokenBroker]
  Broker --> Binding[(identity_binding)]
  HttpExec --> Access[(system_access_profile)]
  Execute --> Biz[OA / ERP / CRM / MCP / 子 Agent]

  AsyncCallback[/system/internal/chat/tasks/events] --> AsyncService[ChatAsyncTaskUpdateService]
  AsyncService --> Publisher[ChatFrontendEventPublisher]
  Publisher --> Events
  Publisher --> Transcript[ChatTranscriptPersistenceService]
  AsyncService --> Projector[AgentTaskProjector]

  Transcript --> ChatDB[(chat_thread / chat_message / checkpoint)]
  Projector --> TaskDB[(agent_task / agent_task_event / user_inbox_notification)]
  Execute --> RuntimeDB[(execution_run / execution_step / approval_request / audit_event)]
```

## 4. 主模型

### 4.1 ToolMeta

运行时真正消费的字段：

- `toolCode`
- `toolName`
- `description`
- `systemCode`
- `parameterSchema`
- `executionPlan`
- `interactionPolicy`
- `riskLevel`
- `requiresAuth`
- `requiresConfirm`
- `status`
- `version`

其中：

- `parameterSchema` 决定表单字段、候选项、默认值、展示方式
- `executionPlan` 决定调用企业系统的步骤
- `interactionPolicy` 决定工具分层、执行模式和交互行为

### 4.2 线程与消息读侧

线程相关表：

- `chat_thread`：线程摘要、状态、阶段、待处理卡片类型
- `chat_message`：消息、表单卡、任务卡、结果卡
- `checkpoint`：执行恢复快照

### 4.3 执行与任务读侧

执行相关表：

- `execution_run`
- `execution_step`
- `approval_request`
- `audit_event`

长任务相关表：

- `agent_task`
- `agent_task_event`
- `user_inbox_notification`

其中 `V32__extend_agent_task_async_flags.sql` 已为 `agent_task` 增加：

- `background`：是否后台任务
- `detached`：是否从主聊天流分离展示

## 5. 会话主链

### 5.1 登录

前端先调用 `/system/auth/login`：

- 本地账号从 `local_user_account` 校验
- 登录成功后签发 `accessToken`
- 后续聊天请求自动注入 `assistantUid/systemCode`

### 5.2 发起对话

前端调用 `/api/chat/run_sse`：

- `ChatController` 从登录态注入 `assistantUid`
- 自动补齐 `systemCode/threadId`
- 统一进入结构化 SSE 事件流

### 5.3 表单采集

`slot_collect` 负责：

- 解析 `parameterSchema.slots`
- 通过 `ToolBackedSlotOptionResolver` 调用内部查询工具
- 补齐候选项、默认值、展示信息
- 输出 `FORM_STATE`

### 5.4 待确认与恢复

`slot_confirm` 负责：

- 生成确认卡片
- 状态切成 `WAITING_CONFIRMATION / CONFIRMING`
- 落库后可通过线程状态和消息接口恢复

恢复规则：

- `resume_sse` 首条阶段事件必须复用线程当前持久化阶段
- 对 `WAITING_INPUT / WAITING_CONFIRMATION / WAITING_APPROVAL` 这类未完成线程，如果本次恢复没有 `toolFeedbacks`，后端直接回放当前 `FORM_STATE` 或 `TASK_STATE`
- 不会为了恢复页面再调用一次模型生成说明文案

### 5.5 执行

`artifact_execute` 负责：

- 根据 `executionPlan` 调用企业接口
- 通过 `TokenExchangeTokenBroker` 获取用户级 token
- 写入执行态、任务态、通知态
- 向前端发出 `EXECUTION_PROGRESS / RESULT / TASK_STATE`

## 6. 状态模型

### 6.1 前端稳定阶段

前端只需要识别这些阶段：

- `UNDERSTANDING`
- `COLLECTING`
- `CONFIRMING`
- `EXECUTING`
- `WAITING_APPROVAL`
- `DONE`
- `ERROR`

### 6.2 线程状态

线程摘要和恢复页使用这些状态：

- `WAITING_INPUT`
- `WAITING_CONFIRMATION`
- `RUNNING`
- `WAITING_APPROVAL`
- `COMPLETED`
- `FAILED`

### 6.3 待确认的正式语义

待确认不是文案推断，而是正式状态：

- `status = WAITING_CONFIRMATION`
- `phase = CONFIRMING`
- `pendingCardType = FORM_CARD`

### 6.4 长任务语义

长任务统一走 `TASK_STATE`：

- `background=true`：表示后台执行，不阻塞主会话
- `detached=true`：表示允许在聊天主线中折叠展示
- `display.foldable=true`：任务卡可以折叠
- `display.collapsedByDefault=true`：默认折叠
- `action.type=TASK_DETAIL`：前端应跳任务详情
- `summaryText`：运行中可取最新实时输出，终态必须优先取 `resultPreview` 摘要，线程 `lastMessage` 和任务列表摘要沿用同一规则

## 7. 异步任务与线程级事件流

### 7.1 内部回调写侧

子 agent / MCP / 后台任务统一通过内部接口回调：

- `POST /system/internal/chat/tasks/events`

写侧链路：

- `SystemInternalTaskEventController`
- `ChatAsyncTaskUpdateService`
- `ChatFrontendEventPublisher`
- `AgentTaskProjector`

落地结果：

- `TASK_STATE` 事件写入 `chat_message`
- 线程摘要更新到 `chat_thread`
- 任务主表写入 `agent_task`
- 任务时间线写入 `agent_task_event`
- 终态通知写入 `user_inbox_notification`

### 7.2 前端实时订阅读侧

线程级实时订阅接口：

- `GET /api/chat/threads/{threadId}/events_sse`

用途：

- 已打开线程页面时接收后续长任务事件
- 不必轮询线程状态和任务列表

实现：

- `FrontendEventStreamRegistry` 使用 `replay().limit(256)` 保留当前进程内最近事件
- 前端仍然必须按 `eventId` 去重
- 页面初始化不能只依赖 `events_sse`，必须先读 `state/messages/tasks/notifications`

## 8. 周期型业务规则

`gougu_oa.work_report` 采用的是全局规则，不是某一次对话的特判：

- 第一优先级槽位是 `types`
- `types=1` 表示日报，自动推导当天范围
- `types=2` 表示周报，自动推导自然周范围
- `types=3` 表示月报，自动推导自然月范围
- `range_date` 和 `submit_range_date` 由计算字段自动生成

相关通用计算函数：

- `period_preset`
- `date_range_label`
- `selector_switch`

## 9. 默认空间

迁移模式默认使用：

- `space_code=default`
- `environment=prod`

如果前端和企业接入方没有显式传递空间上下文，后端会自动回退到该默认空间，并把解析后的 `space_id / space_code / space_environment` 写入执行态与聊天状态。

## 10. 排障顺序

### 10.1 登录异常

先看：

- `SystemAuthProxyController`
- `MigrationAuthService`
- `local_user_account`

### 10.2 企业系统提示未登录

先看：

- `identity_binding`
- `system_access_profile`
- `TokenExchangeTokenBroker`

### 10.3 候选项不对或默认值异常

先看：

- `parameterSchema` 里的 `options.source`
- `ToolBackedSlotOptionResolver`
- 对应 resolver 工具的 `executionPlan`

### 10.4 对话没有进入业务执行

先看：

- `ChatController`
- `V3ProtocolAdapter`
- `tool_meta` 里的 `visibility / invocationPolicy / status`

### 10.5 长任务没有在前端正确展示

先看：

- `TASK_STATE` 的 `background / detached / display / action / notification`
- `ChatAsyncTaskUpdateService`
- `AgentTaskProjector`
- `ChatTaskService`
- `agent_task / agent_task_event / user_inbox_notification`

### 10.6 线程已完成但页面没刷新

先看：

- `/api/chat/threads/{threadId}/events_sse`
- `FrontendEventStreamRegistry`
- 前端是否先拉了 `state/messages/tasks/notifications`
- 前端是否按 `eventId` 做了去重

## 11. 开发约束

后续新增能力必须遵守：

- 新业务动作只定义在 `tool_meta`
- 新查询型候选项只定义成 `QUERY` 工具
- resolver 工具必须使用 `INTERNAL + DEPENDENCY_ONLY`
- 写操作必须通过 `executionPlan`
- 前端状态以 API 协议和读侧表为准，不以日志文案为准
- 长任务必须统一建模成 `TASK_STATE`，不要混入普通聊天文本


