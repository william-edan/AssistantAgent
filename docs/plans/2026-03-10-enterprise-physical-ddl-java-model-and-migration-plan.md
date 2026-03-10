# 企业私有化 OpenClaw 平台物理 DDL 与 Java 模型迁移草案

> 日期：2026-03-10
> 适用项目：`D:/devfive/AssistantAgent`
> 前置文档：
> - `docs/plans/2026-03-10-enterprise-private-openclaw-platform-design.md`
> - `docs/plans/2026-03-10-enterprise-control-plane-schema-and-runtime-design.md`
> 目标：给出第一版物理 DDL 草案、Java 包结构与类清单，以及从当前 `migration` 模式演进到新控制面的分阶段迁移路线。

---

## 1. 定位

这份文档回答三个问题：

1. 新模型第一版如何落到 MySQL DDL。
2. Java 代码在当前多模块仓库里如何组织。
3. 迁移应按什么阶段推进，避免一次性切断旧链路。

约束如下：

1. 延续当前仓库的 MySQL 风格：`BIGINT AUTO_INCREMENT`、`DATETIME`、`status/version`、`CURRENT_TIMESTAMP`。
2. 新控制面对象优先落到 `assistant-controlplane`。
3. 新运行时编排与解析对象优先落到 `assistant-runtime` 和 `assistant-execution`。
4. 旧表保留，优先增量引入新表，再由编译器生成兼容产物。

---

## 2. 推荐迁移批次

建议从现有 `V12` 之后继续新增迁移：

1. `V13__create_platform_space.sql`
2. `V14__create_connector_and_auth_profile.sql`
3. `V15__create_principal_binding_v2.sql`
4. `V16__create_reference_query_action_tables.sql`
5. `V17__create_action_and_interaction_tables.sql`
6. `V18__create_workflow_tables.sql`
7. `V19__create_agent_app_tables.sql`
8. `V20__create_execution_runtime_tables.sql`
9. `V21__add_new_model_indexes.sql`
10. `V22__seed_default_space_and_connector.sql`

原则：

1. 先建控制面主表。
2. 再建运行态表。
3. 再做索引和默认 seed。
4. 不在第一阶段删除 `tool_meta`、`assistant_capability_registry`、`system_access_profile`、`identity_binding`。

---

## 3. 物理 DDL 初稿

## 3.1 空间与接入域

### `V13__create_platform_space.sql`

```sql
CREATE TABLE platform_space (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_code      VARCHAR(64) NOT NULL,
    space_name      VARCHAR(128) NOT NULL,
    environment     VARCHAR(16) NOT NULL DEFAULT 'prod',
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_code_env (space_code, environment)
);
```

### `V14__create_connector_and_auth_profile.sql`

```sql
CREATE TABLE connector (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id        BIGINT NOT NULL,
    connector_code  VARCHAR(64) NOT NULL,
    system_code     VARCHAR(64) DEFAULT NULL,
    display_name    VARCHAR(128) NOT NULL,
    protocol_type   VARCHAR(32) NOT NULL,
    environment     VARCHAR(16) NOT NULL DEFAULT 'prod',
    network_zone    VARCHAR(32) NOT NULL DEFAULT 'intranet',
    base_url        VARCHAR(512) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',
    version         INT NOT NULL DEFAULT 1,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_connector_version (space_id, connector_code, version),
    KEY idx_connector_space_status (space_id, status, id)
);

CREATE TABLE auth_profile (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id             BIGINT NOT NULL,
    connector_id         BIGINT NOT NULL,
    auth_profile_code    VARCHAR(64) NOT NULL,
    auth_type            VARCHAR(32) NOT NULL,
    usage_policy         VARCHAR(32) NOT NULL DEFAULT 'read_only',
    token_endpoint       VARCHAR(512),
    token_header_name    VARCHAR(64) DEFAULT 'Authorization',
    token_header_prefix  VARCHAR(64) DEFAULT 'Bearer ',
    audience             VARCHAR(256),
    scopes_json          JSON,
    credential_ref       VARCHAR(256),
    refresh_policy_json  JSON,
    status               VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_connector_auth_profile (connector_id, auth_profile_code),
    KEY idx_auth_profile_space_status (space_id, status, id)
);
```

### `V15__create_principal_binding_v2.sql`

```sql
CREATE TABLE principal_binding_v2 (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                BIGINT NOT NULL,
    connector_id            BIGINT NOT NULL,
    platform_principal_id   VARCHAR(128) NOT NULL,
    platform_principal_type VARCHAR(32) NOT NULL DEFAULT 'user',
    target_principal_type   VARCHAR(32) NOT NULL,
    target_principal_id     VARCHAR(128) NOT NULL,
    scope_constraints_json  JSON,
    priority                INT NOT NULL DEFAULT 100,
    status                  VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_connector_platform_principal (space_id, connector_id, platform_principal_id),
    KEY idx_binding_space_connector_status (space_id, connector_id, status, priority)
);
```

说明：

1. 用 `principal_binding_v2`，避免和旧 `identity_binding` 直接冲突。
2. 第一阶段通过 adapter 从 `principal_binding_v2` 回写或兼容读取 `identity_binding`。

## 3.2 查询、校验、动作、交互域

### `V16__create_reference_query_action_tables.sql`

```sql
CREATE TABLE reference_resolver (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                BIGINT NOT NULL,
    resolver_code           VARCHAR(128) NOT NULL,
    connector_id            BIGINT NOT NULL,
    allowed_auth_profiles_json JSON,
    input_schema_json       JSON,
    output_schema_json      JSON,
    cache_policy_json       JSON,
    staleness_policy_json   JSON,
    visibility              VARCHAR(16) NOT NULL DEFAULT 'internal',
    status                  VARCHAR(16) NOT NULL DEFAULT 'draft',
    version                 INT NOT NULL DEFAULT 1,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_resolver_version (space_id, resolver_code, version),
    KEY idx_resolver_space_status (space_id, status, id)
);

CREATE TABLE business_query_action (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                     BIGINT NOT NULL,
    query_action_code            VARCHAR(128) NOT NULL,
    connector_id                 BIGINT NOT NULL,
    allowed_auth_profiles_json   JSON,
    binding_strategies_json      JSON,
    input_schema_json            JSON,
    output_schema_json           JSON,
    risk_level                   VARCHAR(16) NOT NULL DEFAULT 'LOW',
    result_visibility_policy_json JSON,
    status                       VARCHAR(16) NOT NULL DEFAULT 'draft',
    version                      INT NOT NULL DEFAULT 1,
    created_at                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_query_action_version (space_id, query_action_code, version),
    KEY idx_query_action_space_status (space_id, status, id)
);

CREATE TABLE precondition_check (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                  BIGINT NOT NULL,
    check_code                VARCHAR(128) NOT NULL,
    connector_id              BIGINT NOT NULL,
    allowed_auth_profiles_json JSON,
    binding_strategies_json   JSON,
    input_schema_json         JSON,
    check_expression_json     JSON,
    failure_policy_json       JSON,
    status                    VARCHAR(16) NOT NULL DEFAULT 'draft',
    version                   INT NOT NULL DEFAULT 1,
    created_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_check_version (space_id, check_code, version),
    KEY idx_check_space_status (space_id, status, id)
);
```

### `V17__create_action_and_interaction_tables.sql`

```sql
CREATE TABLE action_spec (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                   BIGINT NOT NULL,
    action_code                VARCHAR(128) NOT NULL,
    connector_id               BIGINT NOT NULL,
    allowed_auth_profiles_json JSON,
    default_auth_profile_code  VARCHAR(64),
    binding_strategies_json    JSON,
    input_schema_json          JSON,
    output_schema_json         JSON,
    idempotency_policy_json    JSON,
    risk_level                 VARCHAR(16) NOT NULL DEFAULT 'LOW',
    approval_policy_id         BIGINT,
    side_effect_level          VARCHAR(32) NOT NULL DEFAULT 'write',
    observability_profile_json JSON,
    status                     VARCHAR(16) NOT NULL DEFAULT 'draft',
    version                    INT NOT NULL DEFAULT 1,
    created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_action_version (space_id, action_code, version),
    KEY idx_action_space_status (space_id, status, id)
);

CREATE TABLE interaction_spec (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                  BIGINT NOT NULL,
    interaction_code          VARCHAR(128) NOT NULL,
    slot_schema_json          JSON,
    ask_strategy_json         JSON,
    auto_fill_rules_json      JSON,
    summary_layout_json       JSON,
    confirmation_policy_json  JSON,
    edit_policy_json          JSON,
    status                    VARCHAR(16) NOT NULL DEFAULT 'draft',
    version                   INT NOT NULL DEFAULT 1,
    created_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_interaction_version (space_id, interaction_code, version),
    KEY idx_interaction_space_status (space_id, status, id)
);
```

## 3.3 工作流与发布域

### `V18__create_workflow_tables.sql`

```sql
CREATE TABLE workflow_spec (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                   BIGINT NOT NULL,
    workflow_code              VARCHAR(128) NOT NULL,
    display_name               VARCHAR(256) NOT NULL,
    interaction_spec_id        BIGINT,
    risk_aggregation_policy    VARCHAR(64) NOT NULL DEFAULT 'max_step_risk',
    approval_aggregation_policy VARCHAR(64) NOT NULL DEFAULT 'strictest_step_policy',
    failure_policy_json        JSON,
    audit_policy_json          JSON,
    status                     VARCHAR(16) NOT NULL DEFAULT 'draft',
    version                    INT NOT NULL DEFAULT 1,
    published_artifact_ref     VARCHAR(256),
    created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_workflow_version (space_id, workflow_code, version),
    KEY idx_workflow_space_status (space_id, status, id)
);

CREATE TABLE workflow_step (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id                BIGINT NOT NULL,
    step_id                    VARCHAR(128) NOT NULL,
    step_name                  VARCHAR(256) NOT NULL,
    step_type                  VARCHAR(32) NOT NULL,
    connector_id               BIGINT,
    target_ref                 VARCHAR(128),
    allowed_auth_profiles_json JSON,
    binding_strategies_json    JSON,
    input_mapping_json         JSON,
    output_mapping_json        JSON,
    depends_on_json            JSON,
    condition_json             JSON,
    join_policy_json           JSON,
    retry_policy_json          JSON,
    timeout_policy_json        JSON,
    approval_gate_json         JSON,
    compensation_target_ref    VARCHAR(128),
    resume_policy_json         JSON,
    step_order                 INT NOT NULL DEFAULT 0,
    status                     VARCHAR(16) NOT NULL DEFAULT 'enabled',
    created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_step (workflow_id, step_id),
    KEY idx_workflow_step_order (workflow_id, step_order)
);
```

### `V19__create_agent_app_tables.sql`

```sql
CREATE TABLE agent_app (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                 BIGINT NOT NULL,
    agent_app_code           VARCHAR(128) NOT NULL,
    display_name             VARCHAR(256) NOT NULL,
    prompt_policy_json       JSON,
    memory_policy_json       JSON,
    approval_strategy_json   JSON,
    status                   VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_agent_app (space_id, agent_app_code)
);

CREATE TABLE agent_app_grant (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_app_id             BIGINT NOT NULL,
    target_type              VARCHAR(32) NOT NULL,
    target_code              VARCHAR(128) NOT NULL,
    grant_mode               VARCHAR(16) NOT NULL DEFAULT 'allow',
    constraints_json         JSON,
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_app_grant (agent_app_id, target_type, target_code)
);
```

## 3.4 运行态域

### `V20__create_execution_runtime_tables.sql`

```sql
CREATE TABLE execution_run (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id                   BIGINT NOT NULL,
    agent_app_code             VARCHAR(128),
    execution_type             VARCHAR(32) NOT NULL,
    target_code                VARCHAR(128) NOT NULL,
    platform_principal_id      VARCHAR(128) NOT NULL,
    thread_id                  VARCHAR(128),
    status                     VARCHAR(16) NOT NULL DEFAULT 'pending',
    risk_level_resolved        VARCHAR(16),
    approval_state             VARCHAR(16),
    started_at                 DATETIME,
    completed_at               DATETIME,
    created_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_execution_run_space_status (space_id, status, created_at),
    KEY idx_execution_run_thread (thread_id)
);

CREATE TABLE execution_step (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_run_id             BIGINT NOT NULL,
    step_id                      VARCHAR(128) NOT NULL,
    step_type                    VARCHAR(32) NOT NULL,
    connector_id                 BIGINT,
    target_ref                   VARCHAR(128),
    allowed_auth_profiles_json   JSON,
    resolved_auth_profile_code   VARCHAR(64),
    resolved_principal_binding_id BIGINT,
    credential_lease_handle      VARCHAR(128),
    status                       VARCHAR(16) NOT NULL DEFAULT 'pending',
    input_snapshot_json          JSON,
    output_snapshot_json         JSON,
    error_snapshot_json          JSON,
    started_at                   DATETIME,
    completed_at                 DATETIME,
    created_at                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_execution_step_run_status (execution_run_id, status, created_at),
    KEY idx_execution_step_target (target_ref)
);

CREATE TABLE approval_request (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_run_id           BIGINT NOT NULL,
    execution_step_id          BIGINT,
    approval_policy_id         BIGINT,
    status                     VARCHAR(16) NOT NULL DEFAULT 'pending',
    request_payload_json       JSON,
    decision_payload_json      JSON,
    requested_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at                 DATETIME,
    KEY idx_approval_request_run_status (execution_run_id, status, requested_at)
);

CREATE TABLE audit_event_v2 (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_run_id           BIGINT,
    execution_step_id          BIGINT,
    event_type                 VARCHAR(64) NOT NULL,
    event_payload_json         JSON,
    occurred_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_event_run_time (execution_run_id, occurred_at),
    KEY idx_audit_event_step_time (execution_step_id, occurred_at),
    KEY idx_audit_event_type_time (event_type, occurred_at)
);
```

说明：

1. 采用 `audit_event_v2`，避免直接撞当前 `audit_event`。
2. `credential_lease` 建议不落关系表，主存 Redis；关系表只保留句柄和快照。

---

## 4. Java 包结构建议

## 4.1 `assistant-controlplane`

建议新增包：

```text
com.alibaba.assistant.agent.controlplane.space
com.alibaba.assistant.agent.controlplane.connector
com.alibaba.assistant.agent.controlplane.authprofile
com.alibaba.assistant.agent.controlplane.binding
com.alibaba.assistant.agent.controlplane.resolver
com.alibaba.assistant.agent.controlplane.queryaction
com.alibaba.assistant.agent.controlplane.precheck
com.alibaba.assistant.agent.controlplane.action
com.alibaba.assistant.agent.controlplane.interaction
com.alibaba.assistant.agent.controlplane.workflow
com.alibaba.assistant.agent.controlplane.agentapp
```

建议类清单：

- `PlatformSpace`
- `PlatformSpaceMapper`
- `PlatformSpaceService`
- `Connector`
- `ConnectorMapper`
- `ConnectorService`
- `AuthProfile`
- `AuthProfileMapper`
- `AuthProfileService`
- `PrincipalBindingV2`
- `PrincipalBindingV2Mapper`
- `PrincipalBindingService`
- `ReferenceResolver`
- `ReferenceResolverMapper`
- `ReferenceResolverService`
- `BusinessQueryAction`
- `BusinessQueryActionMapper`
- `BusinessQueryActionService`
- `PreconditionCheck`
- `PreconditionCheckMapper`
- `PreconditionCheckService`
- `ActionSpec`
- `ActionSpecMapper`
- `ActionSpecService`
- `InteractionSpec`
- `InteractionSpecMapper`
- `InteractionSpecService`
- `WorkflowSpec`
- `WorkflowSpecMapper`
- `WorkflowSpecService`
- `WorkflowStep`
- `WorkflowStepMapper`
- `WorkflowStepService`
- `AgentApp`
- `AgentAppMapper`
- `AgentAppService`
- `AgentAppGrant`
- `AgentAppGrantMapper`
- `AgentAppGrantService`

## 4.2 `assistant-runtime`

建议新增包：

```text
com.alibaba.assistant.agent.runtime.auth
com.alibaba.assistant.agent.runtime.execution
com.alibaba.assistant.agent.runtime.registry
com.alibaba.assistant.agent.runtime.compiler
com.alibaba.assistant.agent.runtime.workflow
```

建议类清单：

- `CredentialBroker`
- `CredentialLease`
- `CredentialBrokerContext`
- `CredentialBrokerResult`
- `AuthProfileResolver`
- `PrincipalBindingResolver`
- `LeaseManager`
- `ConnectorRegistry`
- `RuntimeArtifactCompiler`
- `RuntimeArtifact`
- `WorkflowDefinitionCompiler`
- `WorkflowInvocationPlanner`
- `ExecutionRunStateStore`
- `ExecutionEventPublisher`

## 4.3 `assistant-execution`

建议新增或重构包：

```text
com.alibaba.assistant.agent.execution.runtime
com.alibaba.assistant.agent.execution.runtime.step
com.alibaba.assistant.agent.execution.runtime.policy
com.alibaba.assistant.agent.execution.runtime.resume
```

建议类清单：

- `WorkflowRuntimeEngine`
- `ExecutionRunRepositoryPort`
- `ExecutionStepRepositoryPort`
- `ApprovalRequestRepositoryPort`
- `WorkflowStepExecutor`
- `ReferenceResolverStepExecutor`
- `BusinessQueryStepExecutor`
- `PreconditionCheckStepExecutor`
- `ActionStepExecutor`
- `ApprovalGateExecutor`
- `TransformStepExecutor`
- `WorkflowRetryDecider`
- `WorkflowRiskAggregator`
- `WorkflowApprovalAggregator`
- `ResumeCheckpointManager`

---

## 5. 旧表到新表的迁移策略

## 5.1 保留不动的旧表

第一阶段保留：

1. `tool_meta`
2. `identity_binding`
3. `system_access_profile`
4. `assistant_capability_registry`
5. `local_user_account`
6. `audit_event`

## 5.2 映射关系

| 旧表/对象 | 新表/对象 | 迁移策略 |
|---|---|---|
| `system_access_profile` | `connector + auth_profile` | 拆分导入 |
| `identity_binding` | `principal_binding_v2` | 映射迁移 |
| `assistant_capability_registry` | `reference_resolver + precondition_check + action_spec + interaction_spec + workflow_spec + workflow_step` | 编译式拆分 |
| `tool_meta` | runtime artifact | 新控制面编译生成 |
| `audit_event` | `audit_event_v2` | 双写过渡 |

## 5.3 编译兼容器

第一阶段建议新增一个兼容编译器：

- `LegacyCapabilityCompiler`

职责：

1. 从 `assistant_capability_registry` 读旧定义
2. 拆成新控制面对象的内存模型
3. 生成新的 runtime artifact
4. 同时可回写 `tool_meta` 以兼容旧执行链路

---

## 6. 分阶段迁移实施路线

### 阶段 1：新控制面落表，不切流量

目标：

1. 落 `platform_space / connector / auth_profile / principal_binding_v2`
2. 落 `reference_resolver / business_query_action / precondition_check / action_spec / interaction_spec / workflow_spec / workflow_step`
3. 提供 CRUD service，但不替换现有运行时

完成标准：

1. 新表可建表成功
2. 有默认 `space`
3. 能导入一条 `gougu_oa` connector

### 阶段 2：旧 capability 编译到新控制面对象

目标：

1. 实现 `LegacyCapabilityCompiler`
2. 先针对 `leave_application` 做样例迁移
3. 生成 runtime artifact 与兼容 `tool_meta`

完成标准：

1. 旧 capability 能被拆解
2. 新旧产物在功能上等价

### 阶段 3：运行时接入 `CredentialBroker`

目标：

1. `TokenExchangeTokenBroker` 升级为 `CredentialBroker`
2. 支持 `allowedAuthProfiles -> resolvedAuthProfile`
3. 支持 `PrincipalBindingV2`

完成标准：

1. 单步 Action 能输出认证解析结果
2. `execution_step` 中能看到 `resolved_auth_profile_code`

### 阶段 4：工作流执行引擎替换

目标：

1. 引入 `WorkflowRuntimeEngine`
2. 支持步骤级 step type
3. 支持审批、恢复、重试、事件流

完成标准：

1. `leave_application` 通过新 runtime 跑通
2. SSE 可输出步骤级事件

### 阶段 5：前端与审计切新事件流

目标：

1. 前端接消费 `execution.started / step.completed / approval.required`
2. 审计与审批系统消费 `audit_event_v2`
3. 保留旧接口兼容壳

完成标准：

1. 旧前端可兼容
2. 新事件流可驱动 UI

### 阶段 6：逐步收缩旧模型

目标：

1. `assistant_capability_registry` 停止人工编辑
2. `tool_meta` 改为纯运行时产物
3. `identity_binding` 和 `system_access_profile` 退役

完成标准：

1. 新接入全部走控制面
2. 旧表只读或归档

---

## 7. 首个落地样例建议

优先用这三个能力串起来验证整套新模型：

1. `oa.leave.apply`
2. `oa.expense.apply`
3. `oa.worklog.submit`

原因：

1. 都包含字典查询、用户目录查询、写入动作、审批提交
2. 适合验证 `ReferenceResolver + ActionSpec + WorkflowSpec`
3. 适合验证风险归并和审批门禁

---

## 8. 结论

下一步如果开始真正编码，建议不要再直接修改 `tool_meta` 和 `assistant_capability_registry` 作为主入口，而是按这份 DDL 草案新建控制面表和服务。

简化成一句话：

`先把新控制面和新运行态表落出来，再通过编译器兼容旧能力；不要继续在旧表上叠加复杂语义。`
