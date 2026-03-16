# Assistant Agent 前端对接文档

## 1. 文档目标

这份文档是给前端直接落地接入用的，不要求前端先理解后端源码。

前端按本文档即可完成以下功能：

- 登录并获取访问令牌
- 发起聊天会话并消费 `SSE`
- 展示采集卡、确认卡、结果卡、任务卡
- 展示任务中心和站内信
- 页面刷新后恢复未完成会话
- 展示后台长任务的实时输出和最终结果
- 支持多轮对话补槽、确认提交、恢复继续

本文档的示例都来自已经跑过的真实联调，不是只根据接口定义推测。

已经验证的核心业务：

- 请假申请
- 工作汇报
- 后台长任务 / 子 Agent 任务
- `resume_sse` 恢复未完成线程

## 2. 前端最低实现清单
，建议先按下面 5 个页面或模块实现：

1. 登录页
2. 会话列表页
3. 聊天详情页
4. 任务中心页
5. 站内信抽屉或通知页

推荐的页面职责如下：

| 页面/模块 | 主要接口 | 作用 |
| --- | --- | --- |
| 登录页 | `POST /system/auth/login` | 获取 `accessToken` |
| 会话列表 | `GET /api/chat/threads` | 展示线程标题、状态、未完成标记、未读通知数 |
| 聊天详情 | `GET /api/chat/threads/{threadId}/state`、`GET /api/chat/threads/{threadId}/messages`、`POST /api/chat/run_sse`、`POST /api/chat/resume_sse` | 发送消息、展示卡片、恢复未完成表单、展示执行结果 |
| 任务中心 | `GET /api/chat/tasks`、`GET /api/chat/tasks/{taskId}`、`GET /api/chat/tasks/{taskId}/events` | 展示异步任务和执行明细 |
| 站内信 | `GET /api/chat/notifications`、`POST /api/chat/notifications/{notificationId}/read` | 展示任务完成提醒 |

## 3. 认证

除登录外，所有接口都要求：

```http
Authorization: Bearer <accessToken>
```

登录接口：

```http
POST /system/auth/login
Content-Type: application/json
```

请求体：

```json
{
  "username": "admin",
  "password": "admin123",
  "systemCode": "gougu_oa"
}
```

成功返回：

```json
{
  "code": 0,
  "msg": "",
  "data": {
    "accessToken": "token-value",
    "refreshToken": "refresh-token-value",
    "expiresTime": 1773456000000,
    "userId": 1
  }
}
```

前端只看：

- `code == 0` 代表成功
- `data.accessToken` 用于后续所有接口

## 4. 所有普通 JSON 接口的统一返回格式

除 `SSE` 外，其余接口统一是：

```json
{
  "code": 0,
  "msg": "",
  "data": {}
}
```

前端判断规则：

- `code == 0`：成功
- `code != 0`：失败
- `msg`：给用户看的错误提示或日志提示

## 5. 前端推荐接入顺序

### 5.1 首屏初始化顺序

进入聊天详情页后，建议严格按这个顺序：

1. 调 `GET /api/chat/threads/{threadId}/state`
2. 调 `GET /api/chat/threads/{threadId}/messages`
3. 调 `GET /api/chat/tasks?threadId={threadId}`
4. 调 `GET /api/chat/notifications?limit=50`
5. 再打开 `GET /api/chat/threads/{threadId}/events_sse`

原因：

- `state` 用来恢复当前线程状态
- `messages` 用来恢复聊天时间线
- `tasks` 用来恢复任务卡和任务中心
- `notifications` 用来恢复站内信
- `events_sse` 只负责增量事件，不负责首屏完整恢复

### 5.2 发送消息的顺序

普通聊天发送顺序：

1. 用户输入文本
2. 调 `POST /api/chat/run_sse`
3. 前端边读 `SSE` 边更新页面
4. 流结束后，前端可以按需再读一次 `state`

### 5.3 恢复线程的顺序

发现线程未完成时：

1. 先读 `state`
2. 根据 `pendingCardType` 恢复当前待处理卡片
3. 如果需要恢复流程，调 `POST /api/chat/resume_sse`
4. 如果只是继续确认，通常直接再调 `run_sse` 发送 `确认`

## 6. 核心状态模型

前端不要从聊天文案猜状态，统一只看下面 3 个字段：

- `status`
- `phase`
- `pendingCardType`

常见状态说明：

| status | phase | pendingCardType | 前端含义 |
| --- | --- | --- | --- |
| `WAITING_INPUT` | `COLLECTING` | `FORM_CARD` | 还有字段没收集完，继续补槽 |
| `WAITING_CONFIRMATION` | `CONFIRMING` | `FORM_CARD` | 信息齐了，等待用户确认 |
| `WAITING_APPROVAL` | `EXECUTING` | `TASK_CARD` | 已提交到执行链，等待审批或外部回调 |
| `RUNNING` | `EXECUTING` | `TASK_CARD` | 任务执行中 |
| `COMPLETED` | `DONE` | `RESULT_CARD` | 执行完成，可以展示结果 |
| `FAILED` | `DONE` | `ERROR_CARD` 或 `RESULT_CARD` | 执行失败，展示失败结果 |

前端页面上建议这样映射：

- 会话列表页：展示 `status` 和 `unfinished`
- 聊天详情页顶部：展示 `phase`
- 聊天时间线：展示 `pendingCardType` 对应的卡片
- 底部操作区：根据 `status` 决定显示“继续补充”“确认”“查看任务”“重试”

## 7. SSE 协议

### 7.1 为什么不能直接用浏览器原生 `EventSource`

当前接口要求 `Authorization: Bearer <token>`。

浏览器原生 `EventSource` 很难直接加 `Authorization` 请求头，所以前端推荐使用：

- `fetch + ReadableStream` 自己解析 `SSE`

如果后续网关把认证改成 Cookie，再考虑原生 `EventSource`。

### 7.2 SSE 接口

对外有两类 `SSE`：

- `POST /api/chat/run_sse`
- `POST /api/chat/resume_sse`
- `GET /api/chat/threads/{threadId}/events_sse`

其中：

- `run_sse`：用户发送一轮消息
- `resume_sse`：恢复中断线程
- `events_sse`：聊天详情页已经打开时，持续订阅线程增量事件

### 7.3 SSE 统一事件包

后端每次推送的 `data:` 都是一条 JSON，不会额外写 `event:` 名称。

示例：

```json
{
  "protocolVersion": "2026-03-13",
  "eventId": "evt-001",
  "threadId": "leave-20260316-001",
  "timestamp": "2026-03-16T08:00:00Z",
  "eventType": "FORM_STATE",
  "stage": "CONFIRMING",
  "payload": {}
}
```

字段解释：

| 字段 | 说明 | 前端是否必须处理 |
| --- | --- | --- |
| `protocolVersion` | 当前协议版本 | 否，可记录日志 |
| `eventId` | 事件唯一标识 | 是，必须去重 |
| `threadId` | 所属线程 | 是 |
| `timestamp` | 事件时间 | 否，展示时可用 |
| `eventType` | 事件类型 | 是 |
| `stage` | 当前阶段 | 是 |
| `payload` | 业务数据 | 是 |

### 7.4 前端必须支持的事件类型

- `STAGE`
- `FORM_STATE`
- `TASK_STATE`
- `EXECUTION_PROGRESS`
- `RESULT`
- `MESSAGE`
- `ERROR`

## 8. 可直接复制的 SSE 调用代码

### 8.1 通用 `SSE` 解析函数

```ts
type FrontendEvent = {
  protocolVersion: string;
  eventId: string;
  threadId?: string;
  timestamp: string;
  eventType: "STAGE" | "FORM_STATE" | "TASK_STATE" | "EXECUTION_PROGRESS" | "RESULT" | "MESSAGE" | "ERROR";
  stage: string;
  payload: Record<string, unknown>;
};

export async function consumeSse(
  url: string,
  init: RequestInit,
  onEvent: (event: FrontendEvent) => void
) {
  const response = await fetch(url, {
    ...init,
    headers: {
      Accept: "text/event-stream",
      ...(init.headers || {})
    }
  });

  if (!response.ok || !response.body) {
    throw new Error(`SSE 请求失败: ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split("\n\n");
    buffer = chunks.pop() || "";

    for (const chunk of chunks) {
      const lines = chunk
        .split("\n")
        .map(line => line.trim())
        .filter(Boolean);

      const dataLines = lines
        .filter(line => line.startsWith("data:"))
        .map(line => line.slice(5).trim());

      if (dataLines.length === 0) {
        continue;
      }

      const raw = dataLines.join("\n");
      const parsed = JSON.parse(raw) as FrontendEvent;
      onEvent(parsed);
    }
  }
}
```

### 8.2 发起一轮聊天

```ts
export async function runChatSse(
  baseUrl: string,
  token: string,
  threadId: string,
  content: string,
  onEvent: (event: FrontendEvent) => void
) {
  return consumeSse(`${baseUrl}/api/chat/run_sse`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      threadId,
      newMessage: {
        messageType: "user",
        content
      }
    })
  }, onEvent);
}
```

### 8.3 恢复线程

```ts
export async function resumeChatSse(
  baseUrl: string,
  token: string,
  threadId: string,
  toolFeedbacks: unknown[],
  onEvent: (event: FrontendEvent) => void
) {
  return consumeSse(`${baseUrl}/api/chat/resume_sse`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      threadId,
      toolFeedbacks
    })
  }, onEvent);
}
```

### 8.4 订阅线程增量事件

```ts
export async function subscribeThreadEvents(
  baseUrl: string,
  token: string,
  threadId: string,
  onEvent: (event: FrontendEvent) => void
) {
  return consumeSse(
    `${baseUrl}/api/chat/threads/${threadId}/events_sse`,
    {
      method: "GET",
      headers: {
        Authorization: `Bearer ${token}`
      }
    },
    onEvent
  );
}
```

### 8.5 `eventId` 去重示例

```ts
const seenEventIds = new Set<string>();

function handleFrontendEvent(event: FrontendEvent) {
  if (seenEventIds.has(event.eventId)) {
    return;
  }
  seenEventIds.add(event.eventId);

  switch (event.eventType) {
    case "STAGE":
      updateStage(event.stage, event.payload);
      break;
    case "FORM_STATE":
      renderFormCard(event.payload);
      break;
    case "TASK_STATE":
      renderTaskCard(event.payload);
      break;
    case "EXECUTION_PROGRESS":
      updateExecutionTimeline(event.payload);
      break;
    case "RESULT":
      renderResultCard(event.payload);
      break;
    case "MESSAGE":
      appendAssistantMessage(event.payload);
      break;
    case "ERROR":
      renderErrorCard(event.payload);
      break;
  }
}
```

## 9. 写接口说明

### 9.1 `POST /api/chat/run_sse`

最小请求：

```json
{
  "threadId": "leave-20260316-001",
  "newMessage": {
    "messageType": "user",
    "content": "明天请一天事假，原因个人事务"
  }
}
```

适用场景：

- 发起新问题
- 继续多轮补充信息
- 修改待确认表单
- 最终确认

### 9.2 `POST /api/chat/resume_sse`

典型请求：

```json
{
  "threadId": "leave-20260316-001",
  "toolFeedbacks": [
    {
      "id": "approval-1",
      "name": "submit_approval",
      "arguments": "{}",
      "result": "APPROVED"
    }
  ]
}
```

适用场景：

- 审批回调
- 外部工具反馈
- 页面恢复后，线程需要继续推进

### 9.3 前端修改表单时，当前版本怎么发送

当前版本没有单独的“结构化表单 patch 写接口”。

也就是说，前端如果让用户直接在确认卡上修改值，点击“重新确认”时，仍然要把修改内容转成一条自然语言消息，再调用 `run_sse`。

已经真实验证通过的示例：

- `审批人改成财务领导`
- `日期改成后天`
- `改成处理家事`
- `确认`

前端推荐交互：

1. 用户在表单卡上修改字段
2. 前端把变更拼成一句自然语言
3. 调 `run_sse`
4. 后端返回新的 `FORM_STATE`
5. 前端用新 `FORM_STATE` 覆盖旧卡片

推荐拼装示例：

```ts
function buildLeaveEditMessage(form: {
  typesLabel?: string;
  startDate?: string;
  endDate?: string;
  reason?: string;
  approverLabel?: string;
}) {
  const parts: string[] = [];
  if (form.typesLabel) parts.push(`请假类型改成${form.typesLabel}`);
  if (form.startDate && form.endDate && form.startDate === form.endDate) {
    parts.push(`日期改成${form.startDate}`);
  }
  if (form.reason) parts.push(`原因改成${form.reason}`);
  if (form.approverLabel) parts.push(`审批人改成${form.approverLabel}`);
  return parts.join("，");
}
```

注意：

- 这是当前后端已真实验证可用的方式
- 前端不要自己拼企业系统字段提交到企业接口
- 真正的执行仍由后端根据线程上下文完成

## 10. 读侧接口说明

### 10.1 线程列表

```http
GET /api/chat/threads?limit=20
```

前端主要使用这些字段：

| 字段 | 用途 |
| --- | --- |
| `threadId` | 进入详情页 |
| `title` | 会话标题 |
| `status` | 状态标签 |
| `phase` | 阶段标签 |
| `unfinished` | 是否显示“未完成” |
| `canResume` | 是否显示“继续处理”按钮 |
| `toolCode` | 可选，用于业务图标映射 |
| `pendingCardType` | 当前停在哪种卡片 |
| `activeTaskCount` | 显示任务角标 |
| `unreadNotificationCount` | 显示未读通知角标 |
| `lastMessage` | 列表摘要 |

### 10.2 线程状态

```http
GET /api/chat/threads/{threadId}/state
```

线程状态是聊天详情页最重要的接口。

核心字段：

| 字段 | 前端用途 |
| --- | --- |
| `status` | 判断线程当前状态 |
| `phase` | 展示阶段条 |
| `unfinished` | 判断是否仍在处理中 |
| `canResume` | 是否允许恢复 |
| `toolCode` | 当前业务工具编码 |
| `pendingCardType` | 当前待展示的主卡片类型 |
| `activeTaskCount` | 任务角标 |
| `unreadNotificationCount` | 通知角标 |
| `pendingForm` | 恢复表单卡或确认卡 |
| `lastResult` | 恢复最终结果卡 |
| `tasks[]` | 恢复线程相关任务 |
| `notifications[]` | 恢复线程相关站内信 |

#### 已真实验证的待确认状态示例

请假线程在确认前的真实返回：

```json
{
  "status": "WAITING_CONFIRMATION",
  "phase": "CONFIRMING",
  "unfinished": true,
  "canResume": true,
  "toolCode": "gougu_oa.leave_application",
  "pendingCardType": "FORM_CARD"
}
```

前端动作：

- 恢复确认卡
- 展示“确认”按钮
- 允许用户修改字段并重新发送一轮 `run_sse`

#### 已真实验证的已完成状态示例

请假执行完成后的真实返回：

```json
{
  "status": "COMPLETED",
  "phase": "DONE",
  "unfinished": false,
  "canResume": false,
  "toolCode": "gougu_oa.leave_application",
  "pendingCardType": "RESULT_CARD",
  "lastMessage": "执行完成"
}
```

前端动作：

- 展示结果卡
- 隐藏确认按钮
- 保留查看任务详情和查看站内信入口

### 10.3 历史消息

```http
GET /api/chat/threads/{threadId}/messages?limit=50
```

前端必须按 `messageType` 渲染，不能按文本猜类型。

常见消息类型：

| messageType | 渲染方式 |
| --- | --- |
| `USER_MESSAGE` | 用户气泡 |
| `ASSISTANT_MESSAGE` | 助手文本气泡 |
| `FORM_CARD` | 表单卡 / 确认卡 |
| `TASK_CARD` | 任务卡 |
| `RESULT_CARD` | 结果卡 |
| `ERROR_CARD` | 错误卡 |

### 10.4 任务列表与详情

```http
GET /api/chat/tasks?threadId={threadId}&limit=20
GET /api/chat/tasks/{taskId}
GET /api/chat/tasks/{taskId}/events?limit=20
```

任务详情中，前端重点关心：

| 字段 | 用途 |
| --- | --- |
| `status` | 任务当前状态 |
| `progressPercent` | 进度条 |
| `summaryText` | 任务摘要 |
| `background` | 是否后台任务 |
| `detached` | 是否可和主聊天分离展示 |
| `display` | 控制展示方式 |
| `liveOutput` | 任务运行日志 |
| `resultPreview` | 结果摘要 |
| `action` | 点击后的跳转动作 |

### 10.5 站内信

```http
GET /api/chat/notifications?limit=50
POST /api/chat/notifications/{notificationId}/read
```

通知字段：

| 字段 | 用途 |
| --- | --- |
| `notificationId` | 标记已读 |
| `taskId` | 关联任务 |
| `status` | `UNREAD` / `READ` |
| `title` | 通知标题 |
| `body` | 通知正文 |
| `action` | 点击跳转 |

## 11. 事件类型详细说明

### 11.1 `STAGE`

作用：

- 更新聊天顶部阶段
- 更新当前线程阶段标签

示例：

```json
{
  "eventType": "STAGE",
  "stage": "COLLECTING",
  "payload": {
    "messageType": "tool-request",
    "toolName": "slot_collect"
  }
}
```

前端建议：

- 不渲染成单独聊天气泡
- 只更新页面状态

### 11.2 `FORM_STATE`

`FORM_STATE` 是最重要的前端协议之一。

它既可以表示“还在采集中”，也可以表示“已经待确认”。

顶层示例：

```json
{
  "mode": "CONFIRM",
  "status": "WAITING_CONFIRMATION",
  "phase": "CONFIRMING",
  "message": "All required slots collected.",
  "toolCode": "gougu_oa.leave_application",
  "values": {
    "types": 1,
    "start_date": "2026-03-17",
    "end_date": "2026-03-17"
  },
  "summary": {
    "summaryItems": [],
    "secondaryItems": []
  },
  "canSubmit": true,
  "fields": []
}
```

#### 前端必须处理的顶层字段

| 字段 | 作用 |
| --- | --- |
| `mode` | `COLLECT` 或 `CONFIRM` |
| `status` | `WAITING_INPUT` 或 `WAITING_CONFIRMATION` |
| `phase` | `COLLECTING` 或 `CONFIRMING` |
| `toolCode` | 当前业务编码 |
| `message` | 卡片说明文案 |
| `values` | 当前所有字段值 |
| `fields[]` | 字段定义 |
| `missingFields[]` | 仍缺失的字段 |
| `summary` | 后端已算好的摘要区 |
| `canSubmit` | 是否允许出现“确认”按钮 |

#### 字段定义结构

```json
{
  "name": "check_uids",
  "title": "审批人",
  "type": "string",
  "required": false,
  "editable": true,
  "value": "4",
  "missing": false,
  "uiComponent": "select",
  "options": [
    {
      "label": "人事领导（推荐）",
      "value": "4",
      "disabled": false
    }
  ],
  "displayConfig": {
    "showInSummary": true,
    "summaryOrder": 6,
    "displayType": "avatar"
  }
}
```

#### 字段渲染规则

| 字段属性 | 前端规则 |
| --- | --- |
| `required=true` | 显示必填标记 |
| `missing=true` | 高亮并提示用户继续补充 |
| `editable=false` | 只读展示 |
| `options[]` 非空 | 渲染选择器 |
| `displayConfig.showInSummary=true` | 展示在卡片摘要区 |
| `uiComponent=date` | 日期选择器 |
| `uiComponent=textarea` | 多行输入框 |
| `uiComponent=radio` | 单选组 |
| `uiComponent=select` | 下拉或多选 |

#### `uiComponent` 建议映射

| uiComponent | 推荐组件 |
| --- | --- |
| `text` | 单行输入框 |
| `textarea` | 多行输入框 |
| `number` | 数字输入框 |
| `date` | 日期选择器 |
| `datetime` | 日期时间选择器 |
| `select` | 下拉选择器 |
| `radio` | 单选组 |
| `checkbox` | 多选组 |
| `hidden` | 不渲染，保留值 |

#### 表单卡按钮规则

| 条件 | 建议按钮 |
| --- | --- |
| `status=WAITING_INPUT` | “继续填写” |
| `status=WAITING_CONFIRMATION` 且 `canSubmit=true` | “确认” |
| 允许修改 | “重新生成确认卡” |

### 11.3 `TASK_STATE`

`TASK_STATE` 用于展示执行中的任务卡和后台长任务卡。

顶层示例：

```json
{
  "taskId": "TASK-ASYNC-20260315-224040",
  "taskType": "SUB_AGENT_CALL",
  "title": "销售分析长任务",
  "status": "COMPLETED",
  "phase": "DONE",
  "background": true,
  "detached": true,
  "summaryText": "销售额同比增长 12%"
}
```

前端重点规则：

| 字段 | 规则 |
| --- | --- |
| `background=true` | 显示为后台任务，不阻塞聊天输入 |
| `detached=true` | 聊天里可折叠，同时也要进任务中心 |
| `display.foldable=true` | 允许折叠 |
| `display.collapsedByDefault=true` | 首屏默认折叠 |
| `display.showLiveOutput=true` | 可以展示运行中的实时输出 |
| `display.showResultPreview=true` | 终态展示摘要 |
| `summaryText` | 任务主标题下的摘要文案 |
| `action.type=TASK_DETAIL` | 点击跳任务详情页或任务抽屉 |
| `notification` | 终态时可能出现，前端应同步到站内信 |

### 11.4 `EXECUTION_PROGRESS`

作用：

- 展示同步执行的步骤流转
- 给任务卡补充更细的步骤状态

示例：

```json
{
  "stepId": "submit_approval",
  "stepName": "提交审批",
  "status": "RUNNING",
  "message": "正在提交审批单"
}
```

前端建议：

- 聊天详情页：可折叠显示在任务卡里
- 任务详情页：按时间线展示

### 11.5 `RESULT`

作用：

- 展示最终结果卡
- 更新线程为完成态

请假真实结果示例：

```json
{
  "success": true,
  "artifactCode": "gougu_oa.leave_application",
  "result": {
    "finalOutputs": {
      "leave_id": 1061,
      "message": "操作成功",
      "code": 0,
      "final_message": "操作成功"
    }
  }
}
```

前端建议：

- 结果卡显示“操作成功”
- 如果 `finalOutputs.leave_id` 存在，可高亮显示业务单号
- 保留“查看任务详情”入口

### 11.6 `MESSAGE`

当前 `MESSAGE` 只表示用户可见的普通文本说明，不再透出内部规划旁白。

前端建议：

- 当普通助手消息气泡渲染
- 不要拿它驱动状态机

### 11.7 `ERROR`

作用：

- 显示失败信息
- 允许前端给出“重试”操作

前端建议：

- 渲染错误卡或错误气泡
- 保留原线程上下文，不要自动清空历史

## 12. 从业务出发的详细接入方案

### 12.1 业务一：请假申请

### 场景 A：一句话信息已经齐全

用户输入：

```text
明天请一天事假，原因个人事务
```

前端调用：

```http
POST /api/chat/run_sse
```

真实联调里，前端会收到：

1. `STAGE = UNDERSTANDING`
2. `STAGE = COLLECTING`
3. `FORM_STATE = WAITING_CONFIRMATION / CONFIRMING`

真实 `FORM_STATE` 里包含的主要字段：

- `types`
- `start_date`
- `end_date`
- `reason`
- `duration`
- `check_flow_id`
- `check_uids`
- `check_copy_uids`

前端此时应该展示：

- 请假确认卡
- 审批人默认值
- 审批人候选列表
- “确认”按钮
- “修改后重新确认”能力

### 场景 B：用户修改审批人或原因

已经真实验证通过的改法：

- `审批人改成财务领导`
- `原因改成处理家事`

前端动作：

1. 用户在卡片上修改字段
2. 前端把变更转成自然语言消息
3. 再次调用 `run_sse`
4. 后端返回新的 `FORM_STATE`

真实验证结果：

- 审批人从推荐值 `4` 变成 `5`
- 原因从旧值改成新值
- 新确认卡会覆盖旧确认卡

### 场景 C：用户点击确认

前端直接再发：

```json
{
  "threadId": "live-leave-binding-rename-20260316-000401",
  "newMessage": {
    "messageType": "user",
    "content": "确认"
  }
}
```

真实联调里会收到：

1. `STAGE = EXECUTING`
2. `EXECUTION_PROGRESS`
3. `TASK_STATE`
4. `RESULT`

真实执行结果：

- `leave_id = 1061`
- `submit_approval = COMPLETED`
- 线程最终变成 `COMPLETED / DONE`
- 任务中心出现一条已完成任务
- 站内信出现“请假申请 已完成”

### 前端展示重点

- 确认卡里审批人要支持二次修改
- 推荐审批人要明显标注“推荐”
- 结果卡要显示业务号 `leave_id`

### 12.2 业务二：工作汇报

### 正确的交互目标

工作汇报不是一开始就问所有字段，正确流程是：

1. 先问汇报类型
2. 选择汇报类型后，后端自动补时间范围
3. 再补工作内容
4. 进入确认
5. 再提交

### 已真实验证的多轮对话

第 1 轮：

```text
我要写工作汇报
```

真实 `FORM_STATE`：

- `status = WAITING_INPUT`
- `phase = COLLECTING`
- `toolCode = gougu_oa.work_report`
- 缺失字段里会明确列出 `types`、`works`

第 2 轮：

```text
周报
```

真实结果：

- 后端自动回填：
  - `start_date = 2026-03-09`
  - `end_date = 2026-03-15`
  - `range_date = 2026-03-09 ~ 2026-03-15`
- 仍然停在 `WAITING_INPUT`

第 3 轮：

```text
本周完成工作汇报流程联调和协议清理，下周继续优化任务中心展示
```

真实结果：

- 进入 `WAITING_CONFIRMATION`
- 前端展示确认卡

第 4 轮：

```text
确认
```

真实结果：

- `gougu_oa.work_report` 执行成功
- 返回 `操作成功`
- 线程状态切换到 `COMPLETED / DONE`

### 前端展示重点

- 汇报类型是第一个重点字段
- 类型选完后，日期字段是后端自动算出来的，不要让前端自己算
- 汇报日期范围展示用后端返回的 `range_date`
- `works` 是核心字段，要重点高亮

### 12.3 业务三：后台长任务 / 子 Agent / MCP 任务

### 目标交互

后台长任务的预期体验是：

1. 聊天里出现一张可折叠任务卡
2. 用户可以继续聊天，不被阻塞
3. 任务中心能看到完整任务
4. 完成后发站内信
5. 点击站内信进入任务详情

### 已真实验证的长任务结果

真实任务：

- `taskId = TASK-ASYNC-20260315-224040`
- `title = 销售分析长任务`
- `status = COMPLETED`
- `summaryText = 销售额同比增长 12%`
- `background = true`
- `detached = true`

前端展示规则：

- 聊天区：显示折叠任务卡
- 任务中心：显示完整任务
- 站内信：显示“销售分析已完成”
- 任务详情：展示 `liveOutput` 和 `resultPreview`

真实结果摘要：

```json
{
  "resultPreview": {
    "reportId": "R-ASYNC-002",
    "summary": "销售额同比增长 12%"
  }
}
```

这说明：

- 聊天卡片摘要可以直接用 `summaryText`
- 任务详情页可以展示 `resultPreview`
- 通知跳转可以用 `action.targetId`

### 12.4 业务四：页面刷新后恢复未完成线程

### 已真实验证的恢复链路

某条请假线程停在待确认时，真实 `state` 返回：

```json
{
  "status": "WAITING_CONFIRMATION",
  "phase": "CONFIRMING",
  "pendingCardType": "FORM_CARD",
  "canResume": true
}
```

前端恢复步骤：

1. 读 `state`
2. 读 `messages`
3. 用 `state.pendingForm` 恢复当前正在处理的表单卡
4. 用 `messages` 恢复聊天时间线
5. 如果需要恢复流，再调 `resume_sse`

### `resume_sse` 的真实返回

已经真实验证：当线程停在待确认阶段时，`resume_sse` 的前两条事件就是：

1. `STAGE = CONFIRMING`
2. `FORM_STATE = WAITING_CONFIRMATION`

也就是说：

- 恢复不会重新生成一长段助手说明
- 恢复会直接回放当前确认卡

这对前端非常重要，因为这样页面刷新后不会出现“恢复后内容突然变样”。

## 13. 聊天详情页的推荐实现方式

### 13.1 页面状态结构建议

```ts
type ChatPageState = {
  threadId: string;
  threadState: any | null;
  messages: any[];
  tasks: any[];
  notifications: any[];
  currentStage: string;
  pendingForm: any | null;
  sending: boolean;
  loading: boolean;
};
```

### 13.2 初始化逻辑

```ts
async function loadChatDetail(threadId: string) {
  const [stateRes, messageRes, taskRes, notificationRes] = await Promise.all([
    getThreadState(threadId),
    getThreadMessages(threadId),
    getTasks(threadId),
    getNotifications()
  ]);

  setThreadState(stateRes.data);
  setMessages(messageRes.data.messages);
  setTasks(taskRes.data.tasks);
  setNotifications(notificationRes.data.notifications);

  if (stateRes.data.pendingCardType === "FORM_CARD") {
    setPendingForm(stateRes.data.pendingForm);
  }

  subscribeThreadEvents(threadId);
}
```

### 13.3 收到事件后的页面更新规则

| eventType | 页面更新 |
| --- | --- |
| `STAGE` | 更新顶部阶段 |
| `FORM_STATE` | 覆盖当前表单卡 |
| `TASK_STATE` | 更新任务卡和任务列表 |
| `EXECUTION_PROGRESS` | 更新执行时间线 |
| `RESULT` | 展示结果卡，刷新线程状态 |
| `MESSAGE` | 追加普通文本气泡 |
| `ERROR` | 展示错误卡或 Toast |

### 13.4 页面上哪些数据以哪个接口为准

| 数据 | 以哪个接口为准 |
| --- | --- |
| 当前线程整体状态 | `GET /api/chat/threads/{threadId}/state` |
| 聊天时间线 | `GET /api/chat/threads/{threadId}/messages` |
| 当前待处理表单 | `state.pendingForm` |
| 当前任务列表 | `GET /api/chat/tasks?threadId={threadId}` |
| 当前未读通知 | `GET /api/chat/notifications` |
| 增量更新 | `events_sse` |

### 13.5 前端本地状态推荐拆分

对于前端经验较少的团队，不建议把所有数据都塞进一个超大的对象里。

推荐至少拆成 4 组状态：

1. 线程状态：来自 `state`
2. 聊天时间线：来自 `messages`
3. 任务数据：来自 `tasks` 和 `TASK_STATE`
4. 通知数据：来自 `notifications`

推荐最小结构：

```ts
type ThreadStateStore = {
  currentThreadId: string;
  status: string;
  phase: string;
  pendingCardType: string | null;
  pendingForm: any | null;
  lastResult: any | null;
  canResume: boolean;
  unfinished: boolean;
};

type MessageStore = {
  items: any[];
  eventIds: Set<string>;
};

type TaskStore = {
  list: any[];
  byId: Record<string, any>;
};

type NotificationStore = {
  list: any[];
  unreadCount: number;
};
```

这样拆分的好处是：

- 页面刷新恢复时，`state` 和 `messages` 可以分别加载
- 收到 `TASK_STATE` 时，不会误把任务卡写进普通消息列表
- 标记通知已读时，不会影响聊天区渲染

## 14. 任务中心的推荐实现方式

任务中心可以做成独立页面，也可以做成右侧抽屉。

建议展示：

- 任务标题
- 任务状态
- 进度
- 摘要
- 是否后台任务
- 是否已完成
- 点击查看详情

任务详情建议展示：

- 基本信息
- `liveOutput` 时间线
- `resultPreview`
- 最终跳转动作 `action`

## 15. 站内信的推荐实现方式

站内信最少支持这几个功能：

1. 列表展示
2. 未读高亮
3. 点击跳详情
4. 标记已读

推荐交互：

1. 打开通知抽屉时调 `GET /api/chat/notifications?limit=50`
2. 点击一条通知时，根据 `action.type` 跳转
3. 同时调用 `POST /api/chat/notifications/{notificationId}/read`

## 16. 前端不要做的事情

不要这样做：

- 不要从普通文本推断状态
- 不要自己计算请假时长、汇报周期、审批流
- 不要自己拼企业写接口参数
- 不要认为 `MESSAGE` 一定会出现
- 不要认为 `toolCode` 一定非空
- 不要把 `events_sse` 当首屏数据源
- 不要忽略 `eventId` 去重
- 不要在刷新页面后只恢复 `messages` 不恢复 `state`

## 17. 错误处理与重试建议

### 17.1 普通 JSON 接口失败

处理方式：

- 给用户显示 `msg`
- 允许手动重试

### 17.2 `SSE` 中途断开

处理方式：

1. 提示“连接中断，正在恢复”
2. 重新读取：
   - `state`
   - `messages`
   - `tasks`
   - `notifications`
3. 重新订阅 `events_sse`

### 17.3 表单还没收集完

判断依据：

- `status = WAITING_INPUT`
- `canSubmit = false`

处理方式：

- 高亮 `missingFields`
- 提示用户继续补充

### 17.4 任务失败

判断依据：

- `TASK_STATE.status = FAILED`
- 或 `RESULT.success = false`

处理方式：

- 聊天区展示失败卡
- 任务中心标红
- 保留重试入口

## 18. 已真实验证的核心内容

以下内容不是理论设计，而是已经真实跑通过的：

### 18.1 请假

- 登录成功
- `run_sse` 正常返回 `STAGE -> FORM_STATE -> EXECUTION_PROGRESS -> TASK_STATE -> RESULT`
- 默认审批人会返回推荐值
- 审批人可改为其他员工
- `确认` 后真实创建请假单并提交审批
- 已验证结果：`leave_id = 1061`

### 18.2 工作汇报

- 首轮先问汇报类型
- 选择 `周报` 后自动回填：
  - `2026-03-09`
  - `2026-03-15`
  - `2026-03-09 ~ 2026-03-15`
- 补完工作内容后进入确认
- `确认` 后执行成功

### 18.3 长任务

- 聊天里显示任务卡
- 任务中心显示任务详情
- 站内信正常生成
- 终态摘要正确显示为 `销售额同比增长 12%`

### 18.4 恢复

- 未完成线程刷新后可以从 `state + messages` 恢复
- `resume_sse` 会直接回放待确认卡
- 恢复链路不会重新生成冗余说明文案

## 19. 前端上线前自检清单

上线前至少确认这 12 项：

1. 登录后能拿到并保存 `accessToken`
2. `fetch + SSE` 工具能正常解析流
3. `eventId` 去重已实现
4. 会话列表能展示 `unfinished`
5. 聊天详情能恢复 `pendingForm`
6. `FORM_STATE` 能按 `fields[]` 渲染
7. `WAITING_CONFIRMATION` 时能发送 `确认`
8. `TASK_STATE` 能折叠展示
9. 任务中心能展示 `resultPreview`
10. 站内信能标记已读
11. 页面刷新后能从 `state/messages/tasks/notifications` 恢复
12. 失败时不会因为纯文本判断错误而卡死页面
