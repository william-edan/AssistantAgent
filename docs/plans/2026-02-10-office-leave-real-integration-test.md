# Office 请假两步流程真实集成测试说明

日期：2026-02-10

## 1. 已支持能力

当前代码已支持 Office 两步请假流程的关键能力：

1. DAG 流程定义（`nodes + edges`）
2. 步骤级请求模式 `requestMode=FORM_URLENCODED`
3. 步骤级请求头（如 `X-Requested-With` / `Origin` / `Referer`）
4. 第一步返回 `action_id` 自动提取（兼容 `action_id`、`data.action_id`、`data.id`）
5. 第二步通过 `${node.leave_add.data.action_id}` 透传

关键类：

1. `assistant-agent-start/src/main/java/com/alibaba/assistant/agent/start/saas/app/service/CapabilityWorkflowService.java`
2. `assistant-agent-start/src/main/java/com/alibaba/assistant/agent/start/saas/infrastructure/connector/HttpConnectorInvoker.java`
3. `assistant-agent-start/src/test/java/com/alibaba/assistant/agent/start/saas/OfficeLeaveRealIntegrationTest.java`

---

## 2. 测试前准备

请确认：

1. `office.test` 可从本机访问。
2. 你有有效 `PHPSESSID`（已登录 OA 后从浏览器 Cookie 取）。
3. 审批人 UID/姓名准备好（`check_uids` / `check_uames`）。

---

## 3. 一键运行真实集成测试

在仓库根目录执行（PowerShell）：

```powershell
mvn -pl assistant-agent-start -Dtest=OfficeLeaveRealIntegrationTest test `
  -Doffice.it.enabled=true `
  -Doffice.it.baseUrl=http://office.test `
  -Doffice.it.sessionToken=你的PHPSESSID `
  -Doffice.it.checkUids=32 `
  -Doffice.it.checkUnames=卢萌 `
  -Doffice.it.flowId=1 `
  -Doffice.it.types=1 `
  -Doffice.it.duration=1 `
  -Doffice.it.startDate="2026-02-10 00:00" `
  -Doffice.it.endDate="2026-02-10 00:04" `
  -Doffice.it.reason="集成测试请假"
```

也可使用同名环境变量（例如 `OFFICE_IT_SESSION_TOKEN`、`OFFICE_IT_CHECK_UIDS`、`OFFICE_IT_CHECK_UNAMES`）。

可选参数：

1. `office.it.copyUnames`
2. `office.it.copyUids`

---

## 4. 路由模板（已内置到测试类）

流程逻辑：

1. `leave_add` -> `POST /home/leaves/add`
2. `leave_submit` -> `POST /api/check/submit_check`
3. 边：`leave_add -> leave_submit`

第二步关键字段：

1. `check_name=leaves`
2. `action_id=${node.leave_add.data.action_id}`

---

## 5. 常见问题排查

1. `CONNECTOR_INVOKE_FAILED`
   - 检查 `office.it.sessionToken` 是否过期
   - 检查 `Origin/Referer` 是否与 OA 域名一致
2. 第二步失败（审批提交失败）
   - 检查 `flow_id`、`check_uids`、`check_uames`
   - 先手工抓包确认当前 OA 参数是否变化
3. 返回 `COLLECTING`
   - 请求输入缺少必填字段（由 `slotSchemaJson.required` 决定）

---

## 6. 下一步建议

1. 将 `OfficeLeaveRealIntegrationTest` 迁移到 CI 可控环境（内网联通 + 测试账号）
2. 增加 `OfficeTripRealIntegrationTest`、`OfficeOvertimeRealIntegrationTest`
3. 将 `flow_id` / 审批人改为动态查询节点，减少硬编码依赖
