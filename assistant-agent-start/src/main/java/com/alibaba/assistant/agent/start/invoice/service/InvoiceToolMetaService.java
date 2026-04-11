/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.assistant.agent.start.invoice.service;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.invoice.model.InvoiceApplyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 开票流程 tool_meta 访问服务。
 *
 * <p>所有外部接口统一通过 tool_meta 调用，避免在本地工具中直接拼装 HTTP 请求。</p>
 */
@Service
@Profile("migration")
public class InvoiceToolMetaService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceToolMetaService.class);

    private static final String DEFAULT_TENANT = "default";

    private static final String DEFAULT_PAGE = "1";

    private static final String DEFAULT_LIMIT = "20";

    private static final String FLOW_LOOKUP_ACTION_ID = "0";

    private static final String FLOW_LOOKUP_FLOW_ID = "0";

    public static final String FLOW_CHECK_NAME = "expense";

    /**
     * 开票主体复用报销主体接口。
     */
    public static final String INVOICE_SUBJECT_LOOKUP_TOOL_CODE = "gougu_oa.expense_subject_lookup";

    /**
     * 关联项目复用报销项目接口。
     */
    public static final String PROJECT_LOOKUP_TOOL_CODE = "gougu_oa.expense_project_lookup";

    /**
     * 审批人和抄送人复用 OA 用户目录接口。
     */
    public static final String USER_LOOKUP_TOOL_CODE = "gougu_oa.expense_user_lookup";

    /**
     * 审批流复用通用 flow 查询工具。
     */
    public static final String FLOW_LOOKUP_TOOL_CODE = "gougu_oa.approval_flow_nodes_lookup";

    /**
     * 企业抬头客户模糊搜索工具。
     */
    public static final String CUSTOMER_LOOKUP_TOOL_CODE = "gougu_oa.invoice_customer_lookup";

    /**
     * 开票申请提交工具。
     */
    public static final String INVOICE_ADD_TOOL_CODE = "gougu_oa.invoice_add";

    /**
     * 开票申请审批提交流程工具，对应 /api/check/submit_check。
     */
    public static final String INVOICE_SUBMIT_TOOL_CODE = "gougu_oa.invoice_submit";

    /**
     * submit_check 使用的审批类型标识。
     */
    public static final String INVOICE_CHECK_NAME = "invoice";

    private static final Set<String> USER_CONTAINER_KEYS = Set.of(
            "children",
            "child",
            "list",
            "rows",
            "records",
            "data",
            "users",
            "userlist",
            "user_list",
            "departments",
            "departmentlist",
            "department_list",
            "deptlist",
            "dept_list");

    private final ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    public InvoiceToolMetaService(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询开票主体。
     *
     * <p>字段来源：复用报销主体接口 {@code /home/cate/enterprise}，
     * 表单展示主体名称，但提交到后端时 {@code invoice_subject} 需要使用主体 ID，因此此处统一返回“ID 作为 value，名称作为 label”。</p>
     */
    public List<OptionRecord> listInvoiceSubjects(@Nullable ToolContext toolContext) {
        return parseOptions(
                execute(INVOICE_SUBJECT_LOOKUP_TOOL_CODE, Map.of(), toolContext),
                List.of("id", "value", "subject_id", "cate_id"),
                List.of("title", "name", "label", "subject_name", "cate_name"));
    }

    /**
     * 查询项目列表。
     *
     * <p>字段来源：复用报销项目接口，表单展示项目名称，提交时同时补齐 {@code project_id}/{@code project_name}。</p>
     */
    public List<OptionRecord> listProjects(@Nullable ToolContext toolContext) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", DEFAULT_PAGE);
        query.put("limit", DEFAULT_LIMIT);
        query.put("status", "");
        query.put("cate_id", "");
        query.put("director", "");
        query.put("director_uid", "");
        query.put("keywords", "");
        return parseOptions(
                execute(PROJECT_LOOKUP_TOOL_CODE, Map.of("query", Map.copyOf(query)), toolContext),
                List.of("id", "value", "project_id", "projectid"),
                List.of("title", "name", "label", "project_name", "projectname"));
    }

    /**
     * 查询审批流。
     *
     * <p>接口要求沿用 {@code check_name=expense}，
     * 默认取第一个 flow 作为发票审批流。</p>
     */
    public List<FlowNodeRecord> listFlowNodes(@Nullable ToolContext toolContext) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("check_name", FLOW_CHECK_NAME);
        query.put("action_id", FLOW_LOOKUP_ACTION_ID);
        query.put("flow_id", FLOW_LOOKUP_FLOW_ID);
        return parseFlowNodes(execute(FLOW_LOOKUP_TOOL_CODE, Map.of("query", Map.copyOf(query)), toolContext));
    }

    /**
     * 查询审批人/抄送人目录。
     *
     * <p>字段来源：{@code /api/oa_integration/get_all_users}。</p>
     */
    public List<UserRecord> listUsers(@Nullable ToolContext toolContext) {
        return parseUsers(execute(USER_LOOKUP_TOOL_CODE, Map.of(), toolContext));
    }

    /**
     * 根据开票抬头模糊查询客户。
     *
     * <p>字段来源：{@code /customer/customer/datalist}，
     * 用于企业抬头场景自动补齐税号、开户地址、联系电话等字段。</p>
     */
    public List<CustomerRecord> searchCustomers(@Nullable String keyword, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", DEFAULT_PAGE);
        query.put("limit", DEFAULT_LIMIT);
        query.put("keywords", keyword.trim());
        return parseCustomers(execute(CUSTOMER_LOOKUP_TOOL_CODE, Map.of("query", Map.copyOf(query)), toolContext));
    }

    public Optional<UserRecord> findUserById(String userId, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        return listUsers(toolContext).stream()
                .filter(user -> userId.trim().equals(user.id()))
                .findFirst();
    }

    public Optional<UserRecord> findUserByName(String userName, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(userName)) {
            return Optional.empty();
        }
        String normalized = normalizeComparisonText(userName);
        return listUsers(toolContext).stream()
                .filter(user -> normalized.equals(normalizeComparisonText(user.name()))
                        || normalized.equals(normalizeComparisonText(user.displayName())))
                .findFirst();
    }

    public Optional<OptionRecord> findProjectByValueOrLabel(String rawValue, @Nullable ToolContext toolContext) {
        return matchOption(rawValue, listProjects(toolContext));
    }

    public Optional<OptionRecord> findInvoiceSubjectByValueOrLabel(String rawValue, @Nullable ToolContext toolContext) {
        return matchOption(rawValue, listInvoiceSubjects(toolContext));
    }

    /**
     * 提交开票申请。
     *
     * <p>字段转换逻辑：
     * 1. 结构化表单字段原样映射到 {@code /finance/invoice/add}；
     * 2. 审批流字段 {@code flow_id/check_uids/check_copy_uids} 由节点层补齐；
     * 3. 审批人名称字段使用接口约定的 {@code check_uames/check_copy_unames}。</p>
     */
    public AddResult addInvoice(InvoiceApplyRequest request, @Nullable ToolContext toolContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Optional.ofNullable(request.amount()).orElse(""));
        body.put("invoice_type", Optional.ofNullable(request.invoiceType()).orElse(""));
        body.put("invoice_subject", Optional.ofNullable(request.invoiceSubject()).orElse(""));
        body.put("types", Optional.ofNullable(request.types()).orElse(""));
        body.put("invoice_title", Optional.ofNullable(request.invoiceTitle()).orElse(""));
        body.put("invoice_tax", Optional.ofNullable(request.invoiceTax()).orElse(""));
        body.put("invoice_bank", Optional.ofNullable(request.invoiceBank()).orElse(""));
        body.put("invoice_account", Optional.ofNullable(request.invoiceAccount()).orElse(""));
        body.put("invoice_banking", Optional.ofNullable(request.invoiceBanking()).orElse(""));
        body.put("invoice_phone", Optional.ofNullable(request.invoicePhone()).orElse(""));
        body.put("invoice_address", Optional.ofNullable(request.invoiceAddress()).orElse(""));
        body.put("contract_name", "");
        body.put("contract_id", "0");
        body.put("project_name", Optional.ofNullable(request.projectName()).orElse(""));
        body.put("project_id", Optional.ofNullable(request.projectId()).orElse(""));
        body.put("file", "");
        body.put("file_ids", "");
        body.put("remark", Optional.ofNullable(request.remark()).orElse(""));
        body.put("flow_id", Optional.ofNullable(request.flowId()).orElse(""));
        body.put("check_uames", Optional.ofNullable(request.checkUames()).orElse(""));
        body.put("check_uids", Optional.ofNullable(request.checkUids()).orElse(""));
        body.put("check_copy_unames", Optional.ofNullable(request.checkCopyUnames()).orElse(""));
        body.put("check_copy_uids", Optional.ofNullable(request.checkCopyUids()).orElse(""));

        ToolExecutor.ExecutionResult executionResult = execute(
                INVOICE_ADD_TOOL_CODE,
                Map.of("body", Map.copyOf(body)),
                toolContext);
        String message = resolveMessage(executionResult).orElse("开票申请提交成功");
        String recordId = resolveRecordId(executionResult).orElse(null);
        return new AddResult(true, message, recordId, summarizePayload(executionResult.payload()));
    }

    /**
     * 先新增开票申请，再调用 submit_check 正式发起审批。
     *
     * <p>接口用途：
     * 1. {@code /finance/invoice/add} 负责落库并返回 {@code return_id}；
     * 2. {@code /api/check/submit_check} 负责把这条开票单推入审批中心；
     * 3. {@code id/action_id/check_name} 与合同、附件占位字段统一在这里补齐。</p>
     */
    public AddResult createAndSubmitInvoice(InvoiceApplyRequest request, @Nullable ToolContext toolContext) {
        AddResult addResult = addInvoice(request, toolContext);
        String recordId = Optional.ofNullable(addResult.recordId())
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalStateException("新增开票申请失败：接口未返回 return_id"));

        ToolExecutor.ExecutionResult submitResult = execute(
                INVOICE_SUBMIT_TOOL_CODE,
                Map.of("body", Map.copyOf(buildSubmitBody(request, recordId))),
                toolContext);

        String message = resolveMessage(submitResult)
                .or(() -> Optional.ofNullable(addResult.message()).filter(StringUtils::hasText))
                .orElse("开票申请提交成功");
        return new AddResult(true, message, recordId, summarizePayload(submitResult.payload()));
    }

    /**
     * 组装 submit_check 请求体。
     *
     * <p>转换逻辑：
     * 1. 复用开票表单字段，保证审批看到的数据与新增单据一致；
     * 2. {@code check_name=invoice} 固定声明为开票审批；
     * 3. {@code id/action_id} 使用 invoice/add 返回的 {@code return_id}。</p>
     */
    private Map<String, Object> buildSubmitBody(InvoiceApplyRequest request, String recordId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Optional.ofNullable(request.amount()).orElse(""));
        body.put("invoice_type", Optional.ofNullable(request.invoiceType()).orElse(""));
        body.put("invoice_subject", Optional.ofNullable(request.invoiceSubject()).orElse(""));
        body.put("types", Optional.ofNullable(request.types()).orElse(""));
        body.put("invoice_title", Optional.ofNullable(request.invoiceTitle()).orElse(""));
        body.put("invoice_tax", Optional.ofNullable(request.invoiceTax()).orElse(""));
        body.put("invoice_bank", Optional.ofNullable(request.invoiceBank()).orElse(""));
        body.put("invoice_account", Optional.ofNullable(request.invoiceAccount()).orElse(""));
        body.put("invoice_banking", Optional.ofNullable(request.invoiceBanking()).orElse(""));
        body.put("invoice_phone", Optional.ofNullable(request.invoicePhone()).orElse(""));
        body.put("invoice_address", Optional.ofNullable(request.invoiceAddress()).orElse(""));
        body.put("contract_name", "");
        body.put("contract_id", "0");
        body.put("project_name", Optional.ofNullable(request.projectName()).orElse(""));
        body.put("project_id", Optional.ofNullable(request.projectId()).orElse(""));
        body.put("file", "");
        body.put("file_ids", "");
        body.put("remark", Optional.ofNullable(request.remark()).orElse(""));
        body.put("flow_id", Optional.ofNullable(request.flowId()).orElse(""));
        body.put("check_uames", Optional.ofNullable(request.checkUames()).orElse(""));
        body.put("check_uids", Optional.ofNullable(request.checkUids()).orElse(""));
        body.put("check_copy_unames", Optional.ofNullable(request.checkCopyUnames()).orElse(""));
        body.put("check_copy_uids", Optional.ofNullable(request.checkCopyUids()).orElse(""));
        body.put("id", recordId);
        body.put("check_name", INVOICE_CHECK_NAME);
        body.put("action_id", recordId);
        return body;
    }

    private Optional<OptionRecord> matchOption(String rawValue, List<OptionRecord> options) {
        if (!StringUtils.hasText(rawValue) || options == null || options.isEmpty()) {
            return Optional.empty();
        }
        String normalizedRawValue = normalizeComparisonText(rawValue);
        return options.stream()
                .filter(option -> normalizedRawValue.equals(normalizeComparisonText(option.value()))
                        || normalizedRawValue.equals(normalizeComparisonText(option.label())))
                .findFirst();
    }

    private ToolExecutor.ExecutionResult execute(
            String toolCode,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext) {
        ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                DEFAULT_TENANT,
                toolCode,
                arguments,
                toolContext);
        if (!executionResult.success()) {
            String message = Optional.ofNullable(executionResult.errorMessage())
                    .filter(StringUtils::hasText)
                    .orElse("tool_meta execution failed");
            throw new IllegalStateException(toolCode + " execution failed: " + message);
        }
        log.info("InvoiceToolMetaService#execute - toolCode={}, output={}", toolCode,
                summarizePayload(executionResult.outputFields()));
        return executionResult;
    }

    private List<OptionRecord> parseOptions(
            ToolExecutor.ExecutionResult executionResult,
            List<String> valueAliases,
            List<String> labelAliases) {
        List<OptionRecord> options = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String value = firstText(node, valueAliases.toArray(String[]::new));
            String label = firstText(node, labelAliases.toArray(String[]::new));
            if (StringUtils.hasText(value) && StringUtils.hasText(label)) {
                options.add(new OptionRecord(value, label));
            }
        }
        return List.copyOf(options);
    }

    private List<CustomerRecord> parseCustomers(ToolExecutor.ExecutionResult executionResult) {
        List<CustomerRecord> customers = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String name = firstText(node, "name", "customer_name", "customerName", "title");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            customers.add(new CustomerRecord(
                    firstText(node, "id", "customer_id", "customerId"),
                    name,
                    firstText(node, "tax_num", "taxNum", "invoice_tax"),
                    firstText(node, "tax_bank", "taxBank", "invoice_bank"),
                    firstText(node, "tax_banksn", "taxBanksn", "invoice_account"),
                    firstText(node, "tax_banking", "taxBanking", "invoice_banking"),
                    firstText(node, "tax_mobile", "taxMobile", "mobile", "phone", "tel"),
                    firstText(node, "tax_address", "taxAddress", "address", "invoice_address")));
        }
        return List.copyOf(customers);
    }

    private List<UserRecord> parseUsers(ToolExecutor.ExecutionResult executionResult) {
        Map<String, UserRecord> users = new LinkedHashMap<>();
        collectUsers(resolveDataNode(executionResult), null, null, users);
        return List.copyOf(users.values());
    }

    private void collectUsers(
            JsonNode node,
            @Nullable String inheritedDid,
            @Nullable String inheritedDepartmentName,
            Map<String, UserRecord> users) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectUsers(item, inheritedDid, inheritedDepartmentName, users));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        boolean departmentNode = isDepartmentNode(node);
        String ownDid = firstText(node, "did", "dept_id", "deptId", "department_id", "departmentId");
        String ownDepartmentName = firstText(
                node,
                "department_name",
                "departmentName",
                "department",
                "dept_name",
                "deptName",
                "did_name",
                "department_title",
                "departmentTitle",
                "dept_title",
                "deptTitle");
        if (departmentNode) {
            ownDid = firstNonBlank(ownDid, firstText(node, "id"));
            ownDepartmentName = firstNonBlank(ownDepartmentName, firstText(node, "name", "title", "label"));
        }

        String nextDid = firstNonBlank(ownDid, inheritedDid);
        String nextDepartmentName = firstNonBlank(ownDepartmentName, inheritedDepartmentName);

        toUserRecord(node, nextDid, nextDepartmentName).ifPresent(user -> users.putIfAbsent(user.id(), user));

        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode child = field.getValue();
            if (child != null && (child.isArray() || child.isObject())) {
                collectUsers(child, nextDid, nextDepartmentName, users);
            }
        }
    }

    private Optional<UserRecord> toUserRecord(
            JsonNode node,
            @Nullable String inheritedDid,
            @Nullable String inheritedDepartmentName) {
        if (node == null || !node.isObject() || !looksLikeUserNode(node)) {
            return Optional.empty();
        }

        String id = firstText(node, "id", "uid", "user_id", "userId", "employee_id", "employeeId");
        String rawName = firstText(node, "name", "realname", "real_name", "uname", "nick_name", "nickName", "label", "title");
        if (!StringUtils.hasText(id) || !StringUtils.hasText(rawName)) {
            return Optional.empty();
        }

        String departmentName = Optional.ofNullable(firstNonBlank(
                firstText(
                        node,
                        "department_name",
                        "departmentName",
                        "department",
                        "dept_name",
                        "deptName",
                        "did_name",
                        "department_title",
                        "departmentTitle",
                        "dept_title",
                        "deptTitle"),
                inheritedDepartmentName,
                extractDepartmentName(rawName))).orElse("");
        String did = Optional.ofNullable(firstNonBlank(
                firstText(node, "did", "dept_id", "deptId", "department_id", "departmentId"),
                inheritedDid)).orElse("");
        String name = Optional.ofNullable(firstNonBlank(extractPureUserName(rawName), rawName)).orElse(rawName);
        return Optional.of(new UserRecord(id, name, did, departmentName));
    }

    private boolean looksLikeUserNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (StringUtils.hasText(firstText(
                node,
                "uid",
                "user_id",
                "userId",
                "employee_id",
                "employeeId",
                "realname",
                "real_name",
                "uname"))) {
            return true;
        }
        boolean hasId = StringUtils.hasText(firstText(node, "id"));
        boolean hasName = StringUtils.hasText(firstText(
                node,
                "name",
                "realname",
                "real_name",
                "uname",
                "nick_name",
                "nickName",
                "label",
                "title"));
        return hasId && hasName && !hasContainerChildren(node);
    }

    private boolean isDepartmentNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        boolean hasChildren = hasContainerChildren(node);
        boolean hasDepartmentHint = StringUtils.hasText(firstText(
                node,
                "department_name",
                "departmentName",
                "department",
                "dept_name",
                "deptName",
                "did_name"))
                || StringUtils.hasText(firstText(node, "did", "dept_id", "deptId", "department_id", "departmentId"));
        return hasChildren && (hasDepartmentHint || !looksLikeUserNode(node));
    }

    private boolean hasContainerChildren(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String normalizedKey = normalizeKey(field.getKey());
            JsonNode value = field.getValue();
            if ((value.isArray() || value.isObject()) && USER_CONTAINER_KEYS.contains(normalizedKey)) {
                return true;
            }
        }
        return false;
    }

    private List<FlowNodeRecord> parseFlowNodes(ToolExecutor.ExecutionResult executionResult) {
        List<FlowNodeRecord> flowNodes = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String id = firstText(node, "id", "flow_id");
            String title = firstText(node, "title", "name", "label");
            String flowId = firstText(node, "flow_id", "id");
            if (StringUtils.hasText(flowId) && StringUtils.hasText(title)) {
                flowNodes.add(new FlowNodeRecord(Optional.ofNullable(id).orElse(flowId), title, flowId));
            }
        }
        return List.copyOf(flowNodes);
    }

    private Optional<String> resolveMessage(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        return Optional.ofNullable(firstText(outputNode, "msg", "message", "result_message", "resultMessage"))
                .or(() -> Optional.ofNullable(firstText(payloadNode.path("finalOutputs"), "message", "msg")))
                .or(() -> Optional.ofNullable(firstText(payloadNode, "message", "error")));
    }

    private Optional<String> resolveRecordId(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        JsonNode dataNode = resolveDataNode(executionResult);
        return Optional.ofNullable(firstText(dataNode, "return_id", "record_id", "recordId", "id", "aid"))
                .or(() -> Optional.ofNullable(firstText(outputNode, "return_id", "record_id", "recordId", "id", "aid")))
                .or(() -> Optional.ofNullable(firstText(
                        payloadNode.path("finalOutputs"),
                        "return_id",
                        "record_id",
                        "recordId",
                        "id",
                        "aid")));
    }

    private JsonNode resolveDataNode(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode dataNode = outputNode.path("data");
        if (!dataNode.isMissingNode() && !dataNode.isNull()) {
            return dataNode;
        }
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        JsonNode payloadDataNode = payloadNode.path("finalOutputs").path("data");
        if (!payloadDataNode.isMissingNode() && !payloadDataNode.isNull()) {
            return payloadDataNode;
        }
        return outputNode;
    }

    private List<JsonNode> flattenToNodes(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            node.forEach(result::add);
            return result;
        }
        if (node.isObject()) {
            JsonNode nested = firstContainer(node, "list", "rows", "records", "data");
            if (nested != node) {
                return flattenToNodes(nested);
            }
            return List.of(node);
        }
        return List.of();
    }

    private JsonNode firstContainer(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject() || fieldNames == null) {
            return node;
        }
        for (String fieldName : fieldNames) {
            JsonNode candidate = node.path(fieldName);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                return candidate;
            }
        }
        return node;
    }

    private String firstText(JsonNode node, String... aliases) {
        if (node == null || node.isMissingNode() || node.isNull() || aliases == null) {
            return null;
        }
        if (!node.isObject()) {
            return asText(node.asText());
        }
        for (String alias : aliases) {
            String normalizedAlias = normalizeKey(alias);
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (normalizeKey(entry.getKey()).equals(normalizedAlias)) {
                    String text = asText(entry.getValue().asText());
                    if (StringUtils.hasText(text)) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = asText(value);
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private String extractPureUserName(@Nullable String rawName) {
        NameParts nameParts = splitDisplayName(rawName);
        return nameParts != null ? nameParts.userName() : rawName;
    }

    private String extractDepartmentName(@Nullable String rawName) {
        NameParts nameParts = splitDisplayName(rawName);
        return nameParts != null ? nameParts.departmentName() : null;
    }

    private NameParts splitDisplayName(@Nullable String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return null;
        }
        String[] parts = rawName.trim().split("\\s*-\\s*");
        if (parts.length < 2) {
            return null;
        }
        String userName = asText(parts[0]);
        String departmentName = asText(parts[parts.length - 1]);
        if (!StringUtils.hasText(userName) || !StringUtils.hasText(departmentName)) {
            return null;
        }
        return new NameParts(userName, departmentName);
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private String normalizeComparisonText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    private String asText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private String summarizePayload(Object payload) {
        try {
            String raw = payload instanceof String text ? text : objectMapper.writeValueAsString(payload);
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            return raw.length() > 400 ? raw.substring(0, 400) + "..." : raw;
        }
        catch (Exception exception) {
            return String.valueOf(payload);
        }
    }

    public record OptionRecord(String value, String label) {
    }

    public record UserRecord(String id, String name, String did, String departmentName) {

        public String displayName() {
            if (StringUtils.hasText(departmentName)) {
                return name + " - " + departmentName;
            }
            return name;
        }
    }

    public record FlowNodeRecord(String id, String title, String flowId) {
    }

    public record CustomerRecord(
            String id,
            String name,
            String taxNum,
            String taxBank,
            String taxBanksn,
            String taxBanking,
            String taxMobile,
            String taxAddress) {
    }

    public record AddResult(boolean success, String message, String recordId, String rawPayload) {
    }

    private record NameParts(String userName, String departmentName) {
    }
}
