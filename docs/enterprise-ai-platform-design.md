# 企业 AI 中台设计方案

基于 Assistant Agent 框架构建企业级智能助手平台的技术方案文档。

## 目录

1. [框架评估与局限性分析](#1-框架评估与局限性分析)
2. [技术方案对比](#2-技术方案对比)
3. [整体架构设计](#3-整体架构设计)
4. [能力路由：三层漏斗模型](#4-能力路由三层漏斗模型)
5. [多轮对话与槽位收集](#5-多轮对话与槽位收集)
6. [动态能力注册机制](#6-动态能力注册机制)
7. [外部系统对接方案](#7-外部系统对接方案)
8. [实战案例：勾股OA请假接入](#8-实战案例勾股oa请假接入)
9. [实施路线](#9-实施路线)
10. [基于你的需求的整体评估](#10-基于你的需求的整体评估)
11. [推荐目标架构（SaaS + AssistantAgent）](#11-推荐目标架构saas--assistantagent)
12. [关键机制设计（对应你的4条需求）](#12-关键机制设计对应你的4条需求)
13. [落地实施蓝图（MyBatis-Plus）](#13-落地实施蓝图mybatis-plus)
14. [分阶段交付与验收标准](#14-分阶段交付与验收标准)

---

## 1. 框架评估与局限性分析

### 1.1 Assistant Agent 框架优势

| 特性 | 说明 |
|------|------|
| **Code-as-Action 范式** | AI 生成 Python 代码在 GraalVM 沙箱执行，比传统 Function Calling 更灵活，天然支持复杂编排 |
| **工具注册体系** | `CodeactToolRegistry` + SPI 扩展点设计良好，支持 MCP/HTTP API/自定义工具动态注册 |
| **Evaluation Graph** | `EvaluationService` 基于依赖图的多维评估，可做路由和意图分类 |
| **跨系统集成** | MCP 协议 + OpenAPI 自动解析，客户只需提供 API 描述即可接入 |
| **经验学习** | Experience/Learning 模块支持从交互中学习，逐步提升准确率 |
| **动态 Prompt** | 基于评估结果条件注入上下文，`PromptBuilder` SPI 可扩展 |

### 1.2 搭建企业中台的局限性

#### 多租户支持

```
企业中台核心需求          框架现状
─────────────────────────────────────
租户隔离                  ❌ 无内置支持，CodeContext/State 单进程内存级别
配额管理                  ❌ 无
计费统计                  ❌ 无
租户级配置                ❌ 需自行实现
权限控制                  ❌ 无 RBAC/ABAC
```

#### 持久化

- Experience/Learning 模块默认仅内存存储
- 无分布式会话管理机制
- 知识库搜索是 Mock 实现

#### 安全性

| 风险点 | 现状 |
|--------|------|
| 代码执行沙箱 | GraalVM 提供隔离，`allowIO`/`allowNativeAccess` 可配置，企业级需额外加固 |
| Prompt 注入 | 有拦截器，防护深度有限 |
| 敏感数据泄露 | 无内置脱敏/审计机制 |
| API 认证 | 无内置认证 |

#### 可观测性

- 无分布式链路追踪
- 无详细 Token 消耗统计
- 无 Agent 执行过程可视化

### 1.3 结论

**可行，但 AssistantAgent 只能作为执行引擎层，SaaS 化需要在上层做大量建设。** Code-as-Action 是差异化核心竞争力，SPI 扩展设计足够灵活，改造成本可控。

---

## 2. 技术方案对比

### 2.1 方案对比矩阵

| 方案 | 优劣分析 |
|------|---------|
| **AssistantAgent（推荐）** | Code-as-Action 是差异化优势；SPI 扩展点完善；与阿里云生态（DashScope）集成好；缺点是需要自建 SaaS 层 |
| **LangChain4j + 自研** | Java 生态成熟，但缺少 Code-as-Action 能力，复杂编排表达力不足 |
| **Spring AI 原生** | 只有基础 Function Calling，缺少评估、经验、学习等高级能力 |
| **Dify/FastGPT 等开源平台** | 开箱即用但大多 Python 栈，且 Code-as-Action 能力弱，定制难度大 |
| **自研 Agent 框架** | 完全可控但投入巨大，建议不要从零开始 |

### 2.2 Code-as-Action vs Function Calling

```
传统 Function Calling:
  用户: "查询上月成交客户并同步到ERP"
  → 调用 query_crm_deals()
  → 需要循环调用 create_erp_customer() ← 无法表达循环
  → 失败

Code-as-Action:
  用户: "查询上月成交客户并同步到ERP"
  → AI 生成代码:
    deals = crm_tool.query_deals(date_range="last_month", status="closed")
    for deal in deals:
        erp_tool.create_customer(name=deal["name"], source="CRM-"+deal["id"])
  → 天然支持循环、条件判断、数据转换
```

在对话式操作场景中，Code-as-Action 的优势特别明显：
- 日期计算（"明天"、"下周一"、"请两天假"）→ AI 生成 `timedelta` 代码
- 字段映射（中文类型名 → 数字编码）→ AI 生成字典映射代码
- 多步编排（创建请假 → 获取审批流 → 提交审批）→ AI 生成顺序调用代码
- 复杂逻辑（"请完假后帮我订机票"）→ AI 编排多个能力

---

## 3. 整体架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                        客户接入层 (Tenant Gateway)                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐  │
│  │ Web Chat │  │ API 接入 │  │ SDK 接入 │  │ 企业IM(钉钉/飞书) │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────────┬───────────┘  │
│       └──────────────┴──────────────┴────────────────┘              │
│                              │                                       │
│              ┌───────────────▼────────────────┐                     │
│              │   API Gateway (认证/限流/路由)   │                    │
│              │   - JWT/API Key 验证             │                    │
│              │   - 租户识别 & 限流              │                    │
│              │   - 请求路由                     │                    │
│              └───────────────┬────────────────┘                     │
└──────────────────────────────┼──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                      SaaS 平台层 (Platform)                         │
│                                                                     │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────────┐ │
│  │ 租户管理服务 │  │ 会话管理服务  │  │ 能力市场 & 连接器管理服务   │ │
│  │ - 租户CRUD  │  │ - 会话持久化  │  │ - 能力注册 API             │ │
│  │ - 套餐配额  │  │ - 上下文管理  │  │ - 槽位模型管理             │ │
│  │ - API Key   │  │ - 历史记录    │  │ - 连接器市场(预置集成)     │ │
│  └─────────────┘  └──────────────┘  └────────────────────────────┘ │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────────┐ │
│  │ 权限控制服务 │  │ 计费 & 审计  │  │ 知识库管理                 │ │
│  │ - RBAC      │  │ - Token 计量 │  │ - 文档上传/向量化           │ │
│  │ - 工具级授权 │  │ - 调用计费   │  │ - 租户隔离存储             │ │
│  │ - 数据隔离  │  │ - 操作审计   │  │ - RAG 检索                 │ │
│  └─────────────┘  └──────────────┘  └────────────────────────────┘ │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                   Agent 执行引擎层 (基于 AssistantAgent)              │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │              Tenant-Aware CodeactAgent (改造)                   │ │
│  │  ┌──────────────┐  ┌───────────────┐  ┌────────────────────┐  │ │
│  │  │ 意图评估      │  │ Prompt 组装    │  │ 代码生成 & 执行    │  │ │
│  │  │ Evaluation   │→│ PromptBuilder  │→│ GraalVM Sandbox    │  │ │
│  │  │ Service      │  │ (租户Prompt)   │  │ (租户级资源隔离)   │  │ │
│  │  └──────────────┘  └───────────────┘  └────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌──────────────────────────────────┐  ┌─────────────────────────┐ │
│  │  租户级 CodeactToolRegistry      │  │  三层能力路由引擎        │ │
│  │  - 租户专属工具集                │  │  - FastIntent 快速匹配   │ │
│  │  - 动态加载工具                  │  │  - 向量语义检索          │ │
│  │  - 工具权限过滤                  │  │  - LLM 候选消歧         │ │
│  └──────────────────────────────────┘  └─────────────────────────┘ │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                      客户系统连接层 (Connectors)                     │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────────────┐ │
│  │  ERP   │ │  CRM   │ │  OA    │ │  HR    │ │ 自定义系统(MCP)  │ │
│  │OpenAPI │ │OpenAPI │ │Session │ │  MCP   │ │                  │ │
│  └────────┘ └────────┘ └────────┘ └────────┘ └──────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.1 对 AssistantAgent 的改造点

| 改造项 | 具体内容 |
|--------|---------|
| **CodeactAgent** | 注入 `TenantContext`，按租户加载工具集、Prompt 模板、经验库 |
| **CodeactToolRegistry** | 改为 `TenantAwareToolRegistry`，按 tenantId 隔离工具注册 |
| **GraalCodeExecutor** | 增加租户级资源配额（CPU 时间、内存限制、执行超时） |
| **EvaluationService** | 支持租户自定义评估规则（不同客户的意图分类不同） |
| **ExperienceProvider** | 持久化到数据库，按 tenant_id 隔离 |
| **StateBridge** | 对接 Redis，支持分布式会话状态 |

这些改造都可以通过已有 SPI 扩展点接入，**不需要修改框架核心代码**。

---

## 4. 能力路由：三层漏斗模型

### 4.1 问题

当客户注册了几十上百个能力时，不能把所有能力描述塞进 Prompt 让 LLM 选择——Token 爆炸且准确率下降。需要一个高效的前置路由机制。

### 4.2 三层漏斗设计

```
用户输入: "我想请两天假"
         │
         ▼
┌─────────────────────────────────────────────────┐
│  第一层: 快速匹配 (< 5ms)                        │
│  基于 FastIntent 规则引擎                         │
│                                                  │
│  规则库 (能力注册时自动生成):                       │
│  ┌──────────────────────────────────────────┐   │
│  │ 能力: submit_leave_request               │   │
│  │ triggers:                                │   │
│  │   - keyword: ["请假","休假","年假",       │   │
│  │              "事假","病假","调休"]         │   │
│  │   - regex: "请.{0,5}假|休.{0,3}天"       │   │
│  │   - prefix: ["我要请假","帮我请"]         │   │
│  │   - negativeKeyword: ["请假记录","假期余额"]│  │
│  └──────────────────────────────────────────┘   │
│                                                  │
│  匹配结果:                                       │
│  ├─ 命中1个 → 直接进入该能力 (约80%的明确意图)    │
│  ├─ 命中0个 → 进入第二层                         │
│  └─ 命中多个 → 进入第二层消歧                     │
└────────────────────┬────────────────────────────┘
                     │ 未命中 或 多命中
                     ▼
┌─────────────────────────────────────────────────┐
│  第二层: 语义匹配 (< 100ms)                      │
│  基于向量检索 (实现 SearchProvider SPI)            │
│                                                  │
│  能力注册时自动生成向量:                            │
│  - description + fewShots[].userInput → embedding │
│  - 存入向量库 (按租户隔离)                         │
│                                                  │
│  用户输入 "项目太累了想歇两天"                     │
│  → embedding → 向量相似度检索                     │
│  → Top-K 结果:                                   │
│    1. submit_leave_request (0.82)                │
│    2. query_workload (0.61)                      │
│                                                  │
│  ├─ Top1 得分 > 阈值 且 远超Top2 → 直接命中       │
│  └─ Top1/Top2 得分接近 → 进入第三层               │
└────────────────────┬────────────────────────────┘
                     │ 需要消歧
                     ▼
┌─────────────────────────────────────────────────┐
│  第三层: LLM 精确判定 (< 2s)                     │
│  只把候选能力(2-3个) 交给 LLM 判断                │
│  (实现 EvaluationCriterion SPI)                  │
│                                                  │
│  Prompt:                                         │
│  "用户说: '项目太累了想歇两天'                     │
│   候选: 1.请假申请 2.工作量查询                    │
│   请判断用户意图，不匹配返回 NONE"                 │
│                                                  │
│  ├─ 有明确结果 → 命中                            │
│  └─ 返回 NONE → 兜底: 直接问用户想做什么          │
└─────────────────────────────────────────────────┘
```

### 4.3 为什么要三层

| 问题 | 纯 LLM 方案 | 三层漏斗方案 |
|------|-------------|-------------|
| 200 个能力 | 全塞 Prompt，Token 爆炸 | 第一层过滤 95%，LLM 只看 2-3 个 |
| 延迟 | 每次 LLM 推理 1-3 秒 | 80% 请求 5ms 内命中 |
| 明确意图 "请假" | 杀鸡用牛刀 | 关键词直接命中 |
| 模糊意图 "想歇歇" | 不稳定 | 向量兜底 + LLM 消歧 |
| 成本 | 每次消耗大量 Token | 仅 5-10% 需要 LLM |

### 4.4 与 AssistantAgent SPI 的对接

| 层级 | 对应 SPI | 说明 |
|------|---------|------|
| 第一层 | `FastIntentService` | 已有，注册能力时自动生成匹配规则 |
| 第二层 | `SearchProvider` | 实现 `CapabilitySemanticSearchProvider`，注册能力时自动生成向量 |
| 第三层 | `EvaluationCriterion` | 实现 `capability_disambiguation` 评估标准 |

### 4.5 路由 Hook 编排

```java
/**
 * 能力路由 Hook - 在 CodeactAgent 处理之前执行
 * HookPosition.BEFORE_AGENT
 */
public class CapabilityRoutingHook implements AgentHook {

    @Override
    public void execute(HookContext context) {
        // 如果已有活跃能力（多轮对话中途），跳过路由
        String active = context.getState("active_capability");
        String phase = context.getState("slot_phase");
        if (active != null && !"COMPLETED".equals(phase)) {
            return;  // 继续槽位收集，不重新路由
        }

        String userInput = context.getUserInput();
        String tenantId = context.getTenantId();

        // 第一层: FastIntent
        Optional<FastIntentResult> fast = fastIntentService.match(userInput, tenantId);
        if (fast.isPresent() && fast.get().isSingleMatch()) {
            activateCapability(context, fast.get().getCapabilityId());
            return;
        }

        // 第二层: 语义检索
        List<SearchResultItem> candidates = semanticSearch.search(...);
        if (candidates.size() == 1 && candidates.get(0).getScore() > 0.8) {
            activateCapability(context, candidates.get(0).getId());
            return;
        }
        if (candidates.isEmpty()) return; // 走通用对话

        // 第三层: LLM 消歧
        EvaluationResult result = evaluationService.evaluate(disambiguationSuite, ...);
        String capId = result.getValue("capability_disambiguation");
        if (!"NONE".equals(capId)) {
            activateCapability(context, capId);
        }
    }

    private void activateCapability(HookContext ctx, String capabilityId) {
        ctx.setState("active_capability", capabilityId);
        ctx.setState("slot_schema", slotSchemaRegistry.get(capabilityId));
        ctx.setState("filled_slots", new HashMap<>());
        ctx.setState("slot_phase", "COLLECTING");
    }
}
```

---

## 5. 多轮对话与槽位收集

### 5.1 设计原则

**LLM 主导对话，规则做"检查点"**

- LLM 负责：自然语言理解、灵活提取信息、自然追问、上下文推断
- 规则负责：必填项检查、格式校验、业务规则验证、最终确认

**核心区别于传统方案：槽位提取逻辑不是写死的，而是 AI 根据上下文动态生成 Python 代码来完成。**

### 5.2 能力定义中的槽位模型

客户注册能力时声明需要收集的信息：

```json
{
  "name": "create_leave_request",
  "displayName": "请假申请",
  "description": "为员工创建请假申请并提交审批流程",

  "slots": [
    {
      "name": "leave_type",
      "type": "ENUM",
      "required": true,
      "enumValues": ["事假","年假","调休假","病假","婚假","丧假","产假","陪产假","其他"],
      "prompt": "请问请什么类型的假？",
      "extractionHint": "匹配类型关键词，'生病/看病'→病假，'结婚'→婚假"
    },
    {
      "name": "start_date",
      "type": "DATE",
      "format": "yyyy-MM-dd",
      "required": true,
      "prompt": "请假从哪天开始？",
      "extractionHint": "解析自然语言日期：'明天','下周一','3月15号'"
    },
    {
      "name": "end_date",
      "type": "DATE",
      "format": "yyyy-MM-dd",
      "required": true,
      "prompt": "请假到哪天结束？",
      "extractionHint": "注意推算：'请两天假'→start_date+1天"
    },
    {
      "name": "reason",
      "type": "STRING",
      "required": false,
      "prompt": "请简要说明请假原因（可选）"
    }
  ],

  "confirmation": {
    "enabled": true,
    "template": "确认提交请假申请：\n- 类型：{leave_type}\n- 时间：{start_date} 至 {end_date}\n- 原因：{reason}\n\n确认提交吗？"
  }
}
```

### 5.3 SlotFillingPromptBuilder

核心新增组件，实现 `PromptBuilder` SPI，每轮对话动态注入槽位收集指令：

```java
public class SlotFillingPromptBuilder implements PromptBuilder {

    @Override
    public boolean match(ModelRequest request) {
        return stateHasActiveCapability(request);
    }

    @Override
    public PromptContribution build(ModelRequest request) {
        SlotSchema schema = getActiveSlotSchema(request);
        Map<String, Object> filled = getFilledSlots(request);
        List<SlotDefinition> missing = findMissingRequired(schema, filled);

        // 动态生成指令，注入到系统提示中
        // 告诉 AI：已收集什么、还缺什么、怎么提取、何时确认
        String instruction = buildSlotInstruction(schema, filled, missing);
        return PromptContribution.builder()
            .systemTextToAppend(instruction)
            .build();
    }
}
```

AI 看到指令后，生成 Python 代码完成提取：

```python
# AI 动态生成的槽位提取代码
from datetime import date, timedelta

slots = json.loads(agent_state.get("filled_slots") or "{}")
msg = "张三要请两天年假，从明天开始"

# 提取（AI 根据 extractionHint 动态生成逻辑）
slots["employee_name"] = "张三"
slots["leave_type"] = "年假"
today = date.today()
slots["start_date"] = (today + timedelta(1)).strftime("%Y-%m-%d")
slots["end_date"] = (today + timedelta(2)).strftime("%Y-%m-%d")

agent_state.set("filled_slots", json.dumps(slots))

# 检查缺失
required = ["leave_type", "start_date", "end_date"]
missing = [r for r in required if not slots.get(r)]
if not missing:
    # 渲染确认信息
    ...
```

### 5.4 Code-as-Action 在槽位收集中的优势

| 场景 | 传统规则引擎 | Code-as-Action |
|------|-------------|---------------|
| "请两天假从明天开始" | 需预定义 "X天假" 规则模板 | AI 生成 `timedelta(2)` 代码 |
| "上次请假的类型再来一次" | 无法处理 | AI 生成代码查询历史 |
| "把上周的请假改成下周" | 需独立修改流程 | AI 生成：查询→修改→提交 |
| "3/15"、"下礼拜五"、"元旦后第一天" | 每种格式写解析器 | AI 直接生成日期计算代码 |
| "请完假后帮我订机票" | 两套独立流程 | AI 编排多能力 |

### 5.5 对话状态机

```
IDLE → [路由命中能力] → COLLECTING → [槽位齐全] → CONFIRMING → [用户确认] → EXECUTING → COMPLETED
                            ↑                         │
                            └── [用户修改槽位] ────────┘
```

- **COLLECTING**：AI 每轮提取槽位，追问缺失项
- **CONFIRMING**：渲染确认模板，等待用户确认/修改
- **EXECUTING**：调用客户 API，返回结果
- 多轮过程中 `CapabilityRoutingHook` 跳过重新路由

---

## 6. 动态能力注册机制

### 6.1 注册 API

```
POST /api/v1/tenant/{tenantId}/capabilities        注册能力
GET  /api/v1/tenant/{tenantId}/capabilities        查询列表
PUT  /api/v1/tenant/{tenantId}/capabilities/{id}   更新能力
DELETE /api/v1/tenant/{tenantId}/capabilities/{id}  删除能力
POST /api/v1/tenant/{tenantId}/capabilities/{id}/test  测试调用
```

### 6.2 注册时后台自动执行

```
能力注册请求
    │
    ├─→ 1. 生成 FastIntent 规则 (第一层路由)
    │     keywords + patterns + prefixes → FastIntentConfig
    │     negativeKeywords → 排除规则
    │
    ├─→ 2. 生成语义向量 (第二层路由)
    │     description + fewShots[].userInput → embedding
    │     → 存入向量库 (metadata: tenantId, capabilityId)
    │
    ├─→ 3. 生成 CodeactTool (执行层)
    │     slots + api → 工具实例
    │     → 注册到租户的 CodeactToolRegistry
    │
    └─→ 4. 生成 SlotSchema (对话层)
          slots + confirmation → SlotSchema
          → 注册到 SlotSchemaRegistry
```

### 6.3 需要新增的核心组件

| 组件 | 职责 | 对应 SPI |
|------|------|---------|
| CapabilityRegistryController | REST API | 新增 |
| CapabilityRepository | 持久化能力定义 | 新增 |
| CapabilityToToolConverter | 将 Capability 转化为 CodeactTool + SlotSchema | 新增 |
| SlotSchemaRegistry | 按租户管理槽位模型 | 新增 |
| SlotFillingPromptBuilder | 动态注入槽位收集指令 | `PromptBuilder` SPI |
| SlotFillingEvaluationCriterion | 判断槽位收集状态 | `EvaluationCriterion` SPI |
| CapabilityRoutingHook | 三层路由编排 | `AgentHook` SPI |
| CapabilityFastIntentGenerator | 注册时自动生成匹配规则 | 新增 |
| CapabilitySemanticSearchProvider | 向量语义检索 | `SearchProvider` SPI |

---

## 7. 外部系统对接方案

### 7.1 三种对接方式

```
Agent 生成的 Python 代码
         │
         ▼
    AgentToolBridge (Python→Java)
         │
    ┌────┼────────────────┐
    ▼    ▼                ▼
  HTTP   MCP          自定义
 OpenAPI 协议       CodeactTool
(最简单) (标准)      (最灵活)
```

| 维度 | HTTP OpenAPI | MCP 协议 | 自定义 CodeactTool |
|------|-------------|----------|-------------------|
| 对接难度 | 最简单 | 中等 | 需编码 |
| 适用场景 | 有标准 REST API | 有 MCP Server | 复杂业务/非标系统 |
| 业务校验 | 无 | 有限 | 完全可控 |

### 7.2 非标系统对接：Session 桥接方案

对于没有标准 REST API 的旧系统（如基于 Session 认证的 Web 系统），采用 **Session 桥接** 方式：

```
核心思路: 获取目标系统的 Session Token，直接调用其现有 AJAX 接口

AssistantAgent                          目标系统
    │                                      │
    ├─ POST /api/token ──────────────────→ 验证账号密码
    │  (获取 Session)                       ← 返回 PHPSESSID
    │                                      │
    ├─ POST /原有接口 ───────────────────→ 现有 AJAX 处理
    │  Cookie: PHPSESSID=xxx               ← 返回 JSON
    │  X-Requested-With: XMLHttpRequest    │
    │                                      │
    └─ 所有数据转换由 AI 代码完成            │
       (日期格式、字段映射、编码转换)
```

**说明：该结论只适用于勾股OA这类 Session + AJAX 接口风格系统。**

对于其他客户系统（OAuth2、AK/SK、签名鉴权、CSRF、防重放、mTLS、form-data 等），建议在连接器层显式实现认证与协议适配，不建议完全依赖 AI 在运行时临时生成转换逻辑。

---

## 8. 实战案例：勾股OA请假接入

### 8.1 目标系统分析

**勾股OA (GouGuOA)** — 基于 ThinkPHP 8 的办公自动化系统。

| 项 | 详情 |
|----|------|
| 框架 | ThinkPHP 8.0.3+, PHP 8.0+ |
| 数据库 | MySQL, 表前缀 `oa_` |
| 认证 | Session-Based (`PHPSESSID` + `gougu_admin` Session 变量) |
| 请假表 | `oa_leaves` |
| 审批 | 多步审批流程，`oa_flow` + `oa_flow_step` + `oa_flow_record` |

**请假表 (oa_leaves) 关键字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| types | int(1-9) | 1事假 2年假 3调休假 4病假 5婚假 6丧假 7产假 8陪产假 9其他 |
| start_date | bigint | 开始日期 (Unix 时间戳) |
| end_date | bigint | 结束日期 (Unix 时间戳) |
| start_span | int | 1上午 2下午 |
| end_span | int | 1上午 2下午 |
| duration | decimal(10,1) | 天数 |
| reason | text | 原因 |
| check_status | tinyint | 0待提交 1审批中 2通过 3拒绝 4撤回 |
| admin_id | int | 创建人 ID |

**现有接口（AJAX 返回 JSON）：**

| 接口 | 方法 | 用途 |
|------|------|------|
| `/home/leaves/add` | POST | 创建/编辑请假 |
| `/home/leaves/datalist` | GET | 查询列表 |
| `/home/leaves/view/{id}` | GET | 查询详情 |
| `/api/check/get_flows` | GET | 获取审批流程 |
| `/api/check/submit_check` | POST | 提交审批 |
| `/api/check/flow_check` | POST | 审批操作 |

### 8.2 OA 侧改动：仅新增一个文件

```php
// app/api/controller/Open.php — OA 侧唯一新增文件
<?php
namespace app\api\controller;

use think\facade\Db;
use think\facade\Session;

class Open
{
    /**
     * 获取接入 Token（即 PHPSESSID）
     * POST /api/open/token
     * Body: {"username":"ai_assistant", "password":"xxx"}
     */
    public function token()
    {
        $param = get_params();

        if (empty($param['username']) || empty($param['password'])) {
            return to_assign(1, '用户名和密码不能为空');
        }

        // 认证逻辑与 login_submit 完全一致
        $admin = Db::name('Admin')
            ->where(['username' => $param['username'], 'status' => 1, 'delete_time' => 0])
            ->find();

        if (empty($admin)) {
            $admin = Db::name('Admin')
                ->where(['mobile' => $param['username'], 'status' => 1, 'delete_time' => 0])
                ->find();
        }

        if (empty($admin)) {
            return to_assign(1, '账号不存在或已被禁用');
        }

        if (set_password($param['password'], $admin['salt']) !== $admin['pwd']) {
            return to_assign(1, '密码错误');
        }

        // 创建 Session
        $session_admin = get_config('app.session_admin');
        Session::set($session_admin, $admin['id']);

        return to_assign(0, '获取成功', [
            'token'      => session_id(),
            'expires_in' => 36000,
            'uid'        => $admin['id'],
            'name'       => $admin['name'],
            'did'        => $admin['did'],
        ]);
    }
}
```

OA 中创建专用账号 `ai_assistant`，赋予请假相关权限。

### 8.3 AssistantAgent 侧：OaHttpBridge 工具

```java
/**
 * 通用 OA HTTP 桥接工具
 * Python 侧调用: oa.post("/home/leaves/add", {"types": 2, ...})
 *                oa.get("/home/leaves/datalist", {"limit": 10})
 */
public class OaHttpBridge implements CodeactTool {

    private final String oaBaseUrl;
    private final HttpClient httpClient;
    private String sessionToken;  // PHPSESSID，自动刷新

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = parseJson(toolInput);
        String path = (String) args.get("path");
        Map<String, Object> params = (Map) args.get("params");
        String method = (String) args.getOrDefault("method", "POST");

        // 带上 PHPSESSID + AJAX Header
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(oaBaseUrl + path))
            .header("Cookie", "PHPSESSID=" + sessionToken)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .method(method, formBody(params))
            .build();

        return httpClient.send(request, BodyHandlers.ofString()).body();
    }
}
```

工具描述（注入到 Prompt 中，引导 AI 生成正确调用代码）：

```java
DefaultCodeactToolMetadata.builder()
    .targetClassName("oa")
    .targetClassDescription("""
        勾股OA系统HTTP接口桥接工具。

        接口约定:
        - oa.post(path, params): POST请求
        - oa.get(path, params): GET请求
        - 返回JSON字符串，需 json.loads() 解析
        - 成功: {"code":0, "msg":"...", "data":{...}}
        - 失败: {"code":1, "msg":"错误原因"}

        可用接口:
        1. POST /home/leaves/add — 创建请假
           参数: types(int), start_date(str,yyyy-MM-dd), end_date(str),
                 start_span(int,1上午2下午), end_span(int), duration(float), reason(str)
           类型: 1事假 2年假 3调休假 4病假 5婚假 6丧假 7产假 8陪产假 9其他
           返回: {"code":0,"data":{"return_id":<ID>}}

        2. GET /home/leaves/datalist — 查询列表
           参数: keywords(str), limit(int), page(int)

        3. POST /api/check/get_flows — 获取审批流程
           参数: check_name="leaves"

        4. POST /api/check/submit_check — 提交审批
           参数: check_name="leaves", action_id(int), flow_id(int)
        """)
    .addFewShot(new CodeExample(
        "创建年假并提交审批",
        """
        import json
        r = oa.post("/home/leaves/add", {
            "types": 2, "start_date": "2026-02-10", "end_date": "2026-02-11",
            "start_span": 1, "end_span": 2, "duration": 2.0, "reason": "回老家"
        })
        res = json.loads(r)
        leave_id = res["data"]["return_id"]

        flows = oa.get("/api/check/get_flows", {"check_name": "leaves"})
        flow_id = json.loads(flows)["data"][0]["id"]

        oa.post("/api/check/submit_check", {
            "check_name": "leaves", "action_id": leave_id, "flow_id": flow_id
        })
        """,
        "创建请假并自动提交审批流程"))
    .build();
```

### 8.4 完整对话流程示例

**示例一：信息充足的一轮完成**

```
用户: "明天帮我请一天年假"
  │
  │  路由: "请...假" → 第一层命中 create_leave_request
  │
  │  槽位提取 (AI 生成代码):
  │  ├─ leave_type = "年假"
  │  ├─ start_date = "2026-02-10" (明天)
  │  ├─ end_date = "2026-02-10" (一天)
  │  ├─ start_span = "上午" (默认)
  │  ├─ end_span = "下午" (默认)
  │  └─ employee_id = session.user_id (自动)
  │  → 全部齐全，渲染确认
  ▼
Agent: 确认提交请假申请：
       - 类型：年假
       - 时间：2026-02-10 上午 至 2026-02-10 下午 (1天)
       - 原因：无
       确认提交吗？
  │
用户: "确认"
  │
  │  AI 生成代码调用 OA:
  │  POST /home/leaves/add → 创建
  │  GET  /api/check/get_flows → 获取流程
  │  POST /api/check/submit_check → 提交审批
  ▼
Agent: 请假申请已提交！
       单号：#42 | 时长：1天 | 状态：审批中 | 审批人：王经理
```

**示例二：需要多轮收集**

```
用户: "帮我请个假"
  │
  │  路由命中，槽位提取: 无具体信息
  ▼
Agent: 好的，请问：
       1. 请什么类型的假？
       2. 从哪天开始，到哪天结束？
  │
用户: "病假，后天到大后天，感冒发烧"
  │
  │  leave_type="病假", start_date=后天, end_date=大后天, reason="感冒发烧"
  ▼
Agent: 确认：病假 2026-02-11 至 2026-02-12 (2天)，原因：感冒发烧。提交吗？
  │
用户: "改成明天到后天"          ← 槽位修正
  │
  │  AI 识别修正指令，更新 start_date 和 end_date
  ▼
Agent: 已修改：2026-02-10 至 2026-02-11，其他不变。确认提交吗？
  │
用户: "确认"  →  提交成功
```

**示例三：模糊意图触发语义路由**

```
用户: "项目太累了想歇两天"
  │
  │  第一层: 无关键词命中
  │  第二层: 向量检索 → submit_leave_request (0.82)
  │  → 高置信度命中
  │
  │  槽位提取: "歇两天" → duration=2, 但缺 leave_type/start_date
  ▼
Agent: 理解，想请两天假休息一下。请问：
       1. 请什么类型的假？年假还是事假？
       2. 从哪天开始？
  │
用户: "年假吧，明天开始"
  │
  ▼
Agent: 确认：年假 2026-02-10 至 2026-02-11 (2天)。确认提交吗？
```

### 8.5 Token 管理

```
AssistantAgent 启动时:
  POST /api/open/token {"username":"ai_assistant","password":"xxx"}
  ← {"token":"abc123...", "expires_in":36000}

缓存 token，后续请求带上:
  Cookie: PHPSESSID=abc123...
  X-Requested-With: XMLHttpRequest

10小时后过期 → OaHttpBridge 自动刷新 token
```

### 8.6 扩展到其他 OA 模块

同样的模式可以快速扩展到 OA 的其他模块，但在生产环境建议补齐连接器配置（鉴权、字段映射、错误映射、幂等策略），不要只依赖 fewShots：

| OA 模块 | 现有接口 | 新增改动 |
|---------|---------|---------|
| 出差申请 | `/home/trips/add` | 仅更新 fewShots |
| 加班申请 | `/home/overtimes/add` | 仅更新 fewShots |
| 外出申请 | `/home/outs/add` | 仅更新 fewShots |
| 报销申请 | `/home/expense/add` | 仅更新 fewShots |
| 会议预约 | `/home/meeting_order/add` | 仅更新 fewShots |

在当前勾股OA案例中，PHP 侧只需要新增一个 Token 接口；对其他系统应按连接器模型评估所需改造。

---

## 9. 实施路线

### 阶段一：MVP 验证

- 基于 AssistantAgent 搭建基础对话能力
- OA 侧新增 Token 接口
- 实现 `OaHttpBridge` 工具
- 接入请假能力，验证端到端流程
- 验证多轮对话和槽位收集

### 阶段二：能力扩展 + 路由体系

- 接入 OA 更多模块（出差、加班、报销）
- 实现三层路由漏斗
- 实现 SlotFillingPromptBuilder
- 建设能力注册 API

### 阶段三：SaaS 化

- 多租户隔离（TenantContext + TenantAwareToolRegistry）
- 租户管理、API Key、计费
- 管理控制台
- API Gateway 接入

### 阶段四：规模化

- 预置常见系统连接器（钉钉、飞书、企业微信、金蝶、用友）
- 向量语义路由优化
- 经验学习系统（跨租户通用经验 + 租户私有经验）
- 分布式执行引擎扩展

---

## 10. 基于你的需求的整体评估

### 10.1 你的目标（需求抽象）

你要建设的是一个 **企业 AI 中台 SaaS**，核心能力是：

1. 客户通过接口注册能力（能力市场化）
2. 可对接多个异构系统（OA/ERP/CRM/HR/自研系统）
3. 打通平台用户体系与客户用户体系（身份联邦 + 权限映射）
4. 避免 AI 在未注册能力上“虚拟调用”

这是典型的“**对话入口 + 受控执行**”架构问题，而不仅是一个 Agent 推理问题。

### 10.2 AssistantAgent 是否适合

结论：**适合做执行与推理引擎层，但不适合单独承担完整 SaaS 平台层。**

| 维度 | 适配度 | 说明 |
|------|--------|------|
| 对话理解与代码编排 | 高 | Code-as-Action 对跨系统编排有优势 |
| 动态工具扩展 | 高 | 有 `DynamicCodeactToolFactory`、MCP/HTTP 动态接入能力 |
| 多租户 SaaS 治理 | 中低 | 需你在上层自建租户、计费、审计、限流、权限 |
| 企业级身份联邦 | 低 | 需自建 IAM/SSO/账号映射层 |
| 强约束执行（防幻觉调用） | 中 | 可实现，但需新增“动作网关 + 白名单校验链路” |

### 10.3 与可选方案对比（Java 技术栈）

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| AssistantAgent 纯引擎化 | 编排灵活、跨系统表达力强 | SaaS 治理能力需自建 | 可行 |
| Spring AI / LangChain4j + Function Calling | 生态成熟、易控 | 复杂跨系统编排表达力弱 | 适合轻编排 |
| AssistantAgent + 受控执行网关（推荐） | 保留灵活性，同时强治理 | 架构复杂度更高 | 最匹配你的需求 |

推荐不是“替代 AssistantAgent”，而是“**AssistantAgent + 平台控制层**”的组合。

---

## 11. 推荐目标架构（SaaS + AssistantAgent）

### 11.1 总体架构（控制面 / 执行面分离）

```
客户入口(Web/API/IM)
        |
   API Gateway (租户识别/鉴权/限流)
        |
Conversation Service (会话、上下文、路由)
        |
AssistantAgent Runtime (意图+规划+代码生成)
        |
Action Gateway (强校验执行网关)
        |
Connector Runtime (HTTP/MCP/Session/SDK)
        |
客户系统 (OA/ERP/CRM/HR/自定义)

Control Plane:
- Tenant & Billing
- Capability Registry (版本/发布/回滚)
- Connector Registry (连接配置/鉴权策略)
- Identity Hub (SSO/账号映射/权限策略)
- Observability & Audit
```

### 11.2 为什么要有 Action Gateway

如果没有这个层，AI 生成的调用动作很难做到强约束。Action Gateway 负责：

- 按 `tenant + capability_id + version` 白名单校验
- 入参 schema 校验与默认值补全
- 权限校验（谁可以执行哪个能力）
- 幂等控制（写操作）
- 风险控制（审批、二次确认、熔断）
- 统一审计日志与可观测

这层是“防止未注册能力被调用”的核心。

### 11.3 与当前文档的关系

当前文档第 3-8 章可以保留作为“Agent 执行与 OA 接入样例”，但需要在上层补齐：

- 连接器配置中心（而非 only prompt/fewShots）
- 身份联邦与用户映射
- 执行网关与策略引擎
- 版本治理与发布流程

---

## 12. 关键机制设计（对应你的4条需求）

### 12.1 能力注册接口（需求1）

在现有 CRUD 基础上，建议补齐版本与发布态：

```
POST   /api/v1/tenant/{tenantId}/capabilities                  创建草稿
POST   /api/v1/tenant/{tenantId}/capabilities/{id}/versions    生成版本
POST   /api/v1/tenant/{tenantId}/capabilities/{id}/publish     发布版本
POST   /api/v1/tenant/{tenantId}/capabilities/{id}/rollback    回滚版本
POST   /api/v1/tenant/{tenantId}/capabilities/{id}/test        联调测试
```

能力定义最少应包含：

- `capability_id`（稳定标识，不依赖自然语言名称）
- `version`、`status(draft/canary/stable/deprecated)`
- `input_schema`、`output_schema`
- `execution_mode`（`READ_ONLY` / `MUTATION` / `APPROVAL_REQUIRED`）
- `connector_ref`（绑定连接器）
- `policy_ref`（权限与风控策略）

### 12.2 多系统对接扩展模型（需求2）

建议建立标准连接器抽象，而不是把差异留给 AI 代码临时处理：

```java
interface ConnectorAuthProvider {}      // OAuth2/Session/AKSK/mTLS
interface ConnectorTransport {}         // HTTP/MCP/JDBC/Queue
interface ConnectorCodec {}             // json/form/xml/multipart
interface ConnectorFieldMapper {}       // 字段/枚举/时间格式映射
interface ConnectorErrorMapper {}       // 错误码归一
```

每个系统保存 `ConnectionProfile`（租户隔离）：

- 基础地址、超时、重试、限流
- 鉴权参数与密钥引用
- Header/Cookie/签名策略
- 数据映射规则与错误映射规则

### 12.3 用户体系打通（需求3）

采用“统一身份 + 双向映射”：

1. 平台登录层：支持 OIDC/SAML SSO（对接客户 IdP）
2. 账号映射层：维护 `platform_user_id <-> customer_user_id`
3. 执行身份策略：
   - `SERVICE_ACCOUNT`：系统级机器人账号执行
   - `DELEGATED_USER`：以终端用户身份执行（推荐用于审批、财务等敏感场景）

最小数据模型建议：

- `user_binding(tenant_id, platform_user_id, system_id, external_user_id, mode, status)`
- `credential_ref(tenant_id, system_id, secret_id, expires_at)`
- `permission_binding(tenant_id, platform_role, capability_id, action)`

### 12.4 防止未注册能力被调用（需求4）

采用“5道防线”：

1. Prompt 防线：仅注入已发布能力与工具描述（按租户、按权限过滤）
2. 计划防线：要求模型输出 `capability_id`，非自由文本接口名
3. 编译防线：执行前静态检查代码中的工具名/方法名是否在白名单
4. 网关防线：Action Gateway 二次校验 capability + schema + permission
5. 运行防线：未知能力统一返回 `CAPABILITY_NOT_REGISTERED` 并终止执行

这样即使模型“想象”了一个接口，也无法进入真实执行链路。

### 12.5 你当前场景的落地建议（短期）

结合 `D:\php_work\office`，建议先做一个可控 MVP：

1. 保留你当前的 OA Session 桥接模式，先打通请假/出差/加班三个能力
2. 同时引入 Capability Registry + Action Gateway（即使先做精简版）
3. 给每个能力绑定明确 schema、权限、幂等键策略
4. 做用户映射最小闭环（至少支持 SERVICE_ACCOUNT 与用户绑定查询）

这条路径能最快验证业务价值，同时避免后期大规模返工。

---

## 13. 落地实施蓝图（MyBatis-Plus）

### 13.1 方案选择（Java 技术栈）

| 方案 | 说明 | 适用性 |
|------|------|--------|
| A. Spring Data JPA | 快速 CRUD，复杂查询可读性一般 | 中 |
| B. MyBatis XML + 手写 SQL | 灵活但样板代码多 | 中高 |
| C. **MyBatis-Plus（推荐）** | 兼顾开发效率与 SQL 可控，适合中台多表管理 | **高** |

推荐采用 **方案 C**，原因：

- 你当前技术栈是 Java，MyBatis-Plus 适合中后台高频 CRUD 场景
- 配合租户插件、逻辑删除、分页插件，可快速搭建 SaaS 数据层
- 对关键链路可保留手写 SQL（避免框架黑盒）

### 13.2 模块拆分建议

为了减少对现有 AssistantAgent 核心模块侵入，建议新增 SaaS 业务模块（先放在 `assistant-agent-start`，后续再独立模块化）：

```
assistant-agent-start
└─ src/main/java/com/alibaba/assistant/agent/start/saas
   ├─ controller        // 对外 REST API（能力/连接器/身份绑定）
   ├─ app               // 应用服务编排（发布、回滚、执行入口）
   ├─ domain            // 领域对象与规则（Capability/Connector/Policy）
   ├─ infrastructure
   │  ├─ mybatis        // DO + Mapper + Repository 实现
   │  ├─ gateway        // Action Gateway 实现
   │  └─ connector      // 连接器运行时
   └─ config            // MyBatis-Plus / 租户 / 审计配置
```

### 13.3 核心数据模型（最小闭环）

#### 租户与身份

- `assistant_tenant`：租户主数据
- `assistant_platform_user`：平台用户
- `assistant_user_binding`：平台用户与客户系统用户映射
- `assistant_role` / `assistant_role_permission`：RBAC

#### 连接器与能力

- `assistant_connector`：系统连接定义（OA/ERP/CRM）
- `assistant_connector_auth`：鉴权配置（secret 引用，不落明文）
- `assistant_connector_api`：连接器接口注册表（`apiCode` 白名单）
- `assistant_capability`：能力主表（稳定 capability_id）
- `assistant_capability_version`：版本快照（schema、prompt、路由规则）
- `assistant_capability_publish`：发布记录（draft/canary/stable）

#### 执行与审计

- `assistant_conversation_session`：会话
- `assistant_action_execution`：执行记录（计划、参数、结果、耗时）
- `assistant_audit_log`：审计日志（谁在何时执行了什么）

示例 DDL（可直接作为首版建表草稿）：

```sql
CREATE TABLE assistant_capability (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  domain_code VARCHAR(64) NOT NULL,
  latest_version INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_tenant_capability (tenant_id, capability_id, deleted)
);

CREATE TABLE assistant_capability_version (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  version_no INT NOT NULL,
  connector_id BIGINT NOT NULL,
  input_schema_json JSON NOT NULL,
  output_schema_json JSON NOT NULL,
  slot_schema_json JSON NULL,
  tool_binding_json JSON NOT NULL,
  route_config_json JSON NULL,
  execution_mode VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_tenant_capability_version (tenant_id, capability_id, version_no, deleted)
);

CREATE TABLE assistant_connector_api (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  connector_id BIGINT NOT NULL,
  api_code VARCHAR(128) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  http_method VARCHAR(16) NOT NULL,
  path_template VARCHAR(512) NOT NULL,
  request_schema_json JSON NOT NULL,
  response_schema_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_connector_api (tenant_id, connector_id, api_code, deleted)
);

CREATE TABLE assistant_user_binding (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  platform_user_id VARCHAR(64) NOT NULL,
  system_code VARCHAR(64) NOT NULL,
  external_user_id VARCHAR(128) NOT NULL,
  binding_mode VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_binding (tenant_id, platform_user_id, system_code, deleted)
);

CREATE TABLE assistant_action_execution (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  capability_version INT NOT NULL,
  executor_user_id VARCHAR(64) NOT NULL,
  execution_mode VARCHAR(32) NOT NULL,
  input_json JSON NOT NULL,
  output_json JSON NULL,
  status VARCHAR(32) NOT NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(1024) NULL,
  cost_ms BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_request_id (tenant_id, request_id)
);
```

### 13.4 MyBatis-Plus 落地规范

建议统一以下规范，避免后期维护成本上升：

1. 统一基类 `BaseTenantDO`
   - 字段：`id`、`tenantId`、`deleted`、`createdAt`、`updatedAt`、`createdBy`、`updatedBy`
2. 统一注解
   - `@TableName`、`@TableId(type = IdType.ASSIGN_ID)`、`@TableLogic`
3. 自动填充
   - 通过 `MetaObjectHandler` 自动填充创建/更新字段
4. 插件链（顺序固定）
   - `TenantLineInnerInterceptor`
   - `PaginationInnerInterceptor`
   - `OptimisticLockerInnerInterceptor`
   - `BlockAttackInnerInterceptor`
5. Repository 模式
   - `IService + ServiceImpl` 只做通用 CRUD，复杂查询下沉到 `Mapper.xml`

参考配置（简化）：

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new SaaSTenantLineHandler()));
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
    interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
    return interceptor;
}
```

依赖建议（`assistant-agent-start/pom.xml`）：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
```

配置建议（`application.yml`）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/assistant_saas?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: your_user
    password: your_password
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

### 13.5 API 契约（首批）

#### 能力注册与发布

```
POST /api/v1/tenant/{tenantId}/capabilities
POST /api/v1/tenant/{tenantId}/capabilities/{capabilityId}/versions
POST /api/v1/tenant/{tenantId}/capabilities/{capabilityId}/publish
POST /api/v1/tenant/{tenantId}/capabilities/{capabilityId}/rollback
GET  /api/v1/tenant/{tenantId}/capabilities/{capabilityId}
GET  /api/v1/tenant/{tenantId}/capabilities/recall?query=...&topK=...
```

#### 连接器管理

```
POST /api/v1/tenant/{tenantId}/connectors
PUT  /api/v1/tenant/{tenantId}/connectors/{connectorId}/auth
POST /api/v1/tenant/{tenantId}/connectors/{connectorId}/test
POST /api/v1/tenant/{tenantId}/connectors/{connectorId}/apis
GET  /api/v1/tenant/{tenantId}/connectors/{connectorId}/apis
GET  /api/v1/tenant/{tenantId}/connectors
```

#### 用户体系打通

```
POST /api/v1/tenant/{tenantId}/user-bindings
GET  /api/v1/tenant/{tenantId}/user-bindings/{platformUserId}
DELETE /api/v1/tenant/{tenantId}/user-bindings/{id}
```

#### 对话执行（受控）

```
POST /api/v1/tenant/{tenantId}/conversations/{sessionId}/chat
```

返回体应包含：

- `resolved_capability_id`
- `resolved_version`
- `execution_id`
- `status`（`COLLECTING` / `CONFIRMING` / `EXECUTING` / `DONE` / `REJECTED`）

### 13.6 防“虚拟接口调用”的执行链路（强约束）

```
LLM 生成计划 -> 标准化 capability_id
            -> Capability Registry 校验发布态
            -> Action Gateway 校验 permission + connector + session
            -> Slot Collector 多轮补槽(可返回 COLLECTING)
            -> DAG Workflow Engine 拓扑执行
            -> 每个节点按 apiCode 命中 Connector API Registry 白名单
            -> Connector Runtime(仅授权+系统调用)
            -> Execution Log/Audit 落库
```

关键约束：

- 任何执行必须携带 `capability_id + version`
- 未注册或未发布能力直接拒绝（不进入连接器层）
- `routeConfigJson.steps[*].apiCode` 或 `routeConfigJson.nodes[*].apiCode` 必须命中 `assistant_connector_api` 白名单
- `input + session slot snapshot` 合并后必须满足 `slot_schema_json.required`（或回退到 `input_schema_json.required`）
- 写操作必须带 `request_id`（幂等）

### 13.7 分层架构（DAG + 向量）

当前建议与已落地分层如下：

1. 对话与收集层（Conversation）
   - API：`POST /api/v1/tenant/{tenantId}/conversations/{sessionId}/chat`
   - 会话状态表：`assistant_conversation_session`
   - 输出状态：`COLLECTING / DONE / REJECTED`
   - 职责：多轮槽位收集、请求幂等、执行审计。
2. 能力流程层（Capability Workflow）
   - 配置：`routeConfigJson`
   - 支持两种表达：
     - 线性：`steps[]`
     - 图式：`nodes[] + edges[]`（DAG）
   - 引擎：拓扑排序执行节点，支持 `${input.xxx}`、`${node.<nodeCode>...}` 变量引用。
3. 连接器授权与调用层（Connector Runtime）
   - `ConnectorAuthProvider`：按 `authType` 解析 header/cookie（SESSION/BEARER/BASIC）
   - `ConnectorInvoker`：按 `connectorType` 执行系统调用（HTTP/其他）
   - 约束：不承载业务流程，只承载“授权 + 调用”。
4. 语义召回层（Vector Recall）
   - API：`GET /api/v1/tenant/{tenantId}/capabilities/recall?query=...&topK=...`
   - 作用：从已发布能力中召回候选，不直接触发执行。
   - 安全边界：召回只影响候选排序，最终执行仍受发布态、白名单、权限、槽位校验控制。

### 13.8 DAG 能力定义示例（请假两步）

```json
{
  "nodes": [
    {
      "nodeCode": "leave_add",
      "apiCode": "office_leave_add",
      "requestBody": {
        "start_date": "${input.startDate}",
        "end_date": "${input.endDate}",
        "reason": "${input.reason}"
      }
    },
    {
      "nodeCode": "leave_submit",
      "apiCode": "office_leave_submit",
      "requestBody": {
        "action_id": "${node.leave_add.data.action_id}",
        "check_name": "leaves"
      }
    }
  ],
  "edges": [
    { "from": "leave_add", "to": "leave_submit" }
  ]
}
```

要点：

- 这与勾股 OA 的两步流程一致：先 `/home/leaves/add`，再 `/api/check/submit_check`。
- `action_id` 由第一步返回值映射到第二步入参。
- 业务流程可配置，避免把请假逻辑写死在 invoker。

### 13.9 与 AssistantAgent 集成建议（执行边界）

推荐边界：Assistant 负责“理解和收集”，Workflow 负责“确定性执行”。

1. Assistant 输入：自然语言请求 + 当前会话槽位快照 + 候选能力列表（召回结果）。
2. Assistant 输出：`capability_id` 候选、槽位提取、是否继续追问。
3. Action Gateway 负责最终裁决：
   - 能力发布态
   - 接口白名单
   - 用户绑定与授权
   - DAG 执行和审计

这样可以避免“Assistant 幻觉直接调未注册接口”的风险。

---

## 14. 分阶段交付与验收标准

### 14.1 Phase-1（2~3 周）：可控 MVP

范围：

- 能力注册（创建/发布/查询）
- 勾股OA三能力（请假、出差、加班）
- Action Gateway 最小校验
- MyBatis-Plus 落库（能力、版本、执行记录、用户映射）

验收标准：

- 未注册能力调用被拦截
- 已发布能力可稳定执行，成功率 > 95%
- 全链路可追踪（至少 execution_id + request_id）

### 14.2 Phase-2（3~5 周）：多系统扩展

范围：

- 增加至少 1 个非 OA 系统连接器（ERP/CRM 任一）
- 引入连接器认证策略抽象（OAuth2/AKSK）
- 上线回滚与灰度发布

验收标准：

- 跨系统编排成功（至少 1 条读写链路）
- 能力版本可灰度与回滚
- 连接器错误码归一化

### 14.3 Phase-3（持续迭代）：SaaS 化增强

范围：

- 计费、限流、配额
- 管理台（能力、连接器、用户绑定）
- 安全合规（脱敏、审批、审计报表）

验收标准：

- 租户隔离通过压测与安全测试
- 关键操作全部审计留痕
- 平台稳定性满足生产 SLA

详细开发任务拆分见：

- `docs/plans/2026-02-10-enterprise-ai-platform-mybatisplus-design.md`

---

## 附录

### A. 推荐技术选型

| 组件 | 推荐方案 |
|------|----------|
| API Gateway | Spring Cloud Gateway |
| 认证授权 | Spring Security + OAuth2 |
| ORM 持久层 | MyBatis-Plus |
| 向量数据库 | Milvus / AnalyticDB |
| 消息队列 | RocketMQ / Kafka |
| 缓存 | Redis |
| 监控 | Prometheus + Grafana |
| 链路追踪 | SkyWalking / Jaeger |

### B. AssistantAgent 核心文件参考

| 组件 | 路径 |
|------|------|
| CodeactAgent | `assistant-agent-autoconfigure/.../CodeactAgent.java` |
| GraalCodeExecutor | `assistant-agent-core/.../GraalCodeExecutor.java` |
| CodeactToolRegistry | `assistant-agent-core/.../CodeactToolRegistry.java` |
| DefaultCodeactToolRegistry | `assistant-agent-core/.../DefaultCodeactToolRegistry.java` |
| EvaluationService | `assistant-agent-evaluation/.../EvaluationService.java` |
| FastIntentService | `assistant-agent-extensions/.../FastIntentService.java` |
| SearchProvider SPI | `assistant-agent-extensions/.../SearchProvider.java` |
| PromptBuilder SPI | `assistant-agent-prompt-builder/.../PromptBuilder.java` |
| ReplyChannelDefinition SPI | `assistant-agent-extensions/.../ReplyChannelDefinition.java` |
| DynamicCodeactToolFactory SPI | `assistant-agent-extensions/.../DynamicCodeactToolFactory.java` |
