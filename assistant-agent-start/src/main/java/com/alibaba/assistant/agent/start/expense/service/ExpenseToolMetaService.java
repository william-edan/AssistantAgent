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
package com.alibaba.assistant.agent.start.expense.service;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.expense.model.ExpenseAddDetail;
import com.alibaba.assistant.agent.start.expense.model.ExpenseAddRequest;
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
 * 报销流程 tool_meta 访问服务。
 *
 * <p>所有 OA 接口统一通过 tool_meta 调用，避免工具内直接拼装 HTTP 请求。</p>
 */
@Service
@Profile("migration")
public class ExpenseToolMetaService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseToolMetaService.class);

    private static final String DEFAULT_TENANT = "default";

    private static final String DEFAULT_PROJECT_PAGE = "1";

    private static final String DEFAULT_PROJECT_LIMIT = "20";

    private static final String DEFAULT_PROJECT_STATUS = "";

    private static final String DEFAULT_PROJECT_CATE_ID = "";

    private static final String DEFAULT_PROJECT_DIRECTOR = "";

    private static final String DEFAULT_PROJECT_DIRECTOR_UID = "";

    private static final String DEFAULT_PROJECT_KEYWORDS = "";

    private static final String FLOW_LOOKUP_FLOW_ID = "0";

    public static final String DEFAULT_FLOW_ID = "7";

    public static final String CHECK_NAME = "expense";

    public static final String EXPENSE_SUBJECT_LOOKUP_TOOL_CODE = "gougu_oa.expense_subject_lookup";

    public static final String EXPENSE_CATEGORY_LOOKUP_TOOL_CODE = "gougu_oa.expense_category_lookup";

    public static final String EXPENSE_PROJECT_LOOKUP_TOOL_CODE = "gougu_oa.expense_project_lookup";

    public static final String EXPENSE_USER_LOOKUP_TOOL_CODE = "gougu_oa.expense_user_lookup";

    public static final String APPROVAL_FLOW_NODES_LOOKUP_TOOL_CODE = "gougu_oa.approval_flow_nodes_lookup";

    public static final String EXPENSE_ADD_TOOL_CODE = "gougu_oa.expense_add";

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

    public ExpenseToolMetaService(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询报销主体。
     */
    public List<OptionRecord> listSubjects(@Nullable ToolContext toolContext) {
        return parseOptions(
                execute(EXPENSE_SUBJECT_LOOKUP_TOOL_CODE, Map.of(), toolContext),
                List.of("id", "value", "subject_id", "cate_id"),
                List.of("title", "name", "label", "subject_name", "cate_name"));
    }

    /**
     * 查询报销类型。
     */
    public List<OptionRecord> listExpenseCategories(@Nullable ToolContext toolContext) {
        return parseOptions(
                execute(EXPENSE_CATEGORY_LOOKUP_TOOL_CODE, Map.of(), toolContext),
                List.of("id", "value", "cate_id", "expense_cate_id"),
                List.of("title", "name", "label", "cate_name", "expense_cate_name"));
    }

    /**
     * 查询项目列表，默认参数为 page=1、limit=20，其它筛选条件为空。
     */
    public List<OptionRecord> listProjects(@Nullable ToolContext toolContext) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", DEFAULT_PROJECT_PAGE);
        query.put("limit", DEFAULT_PROJECT_LIMIT);
        query.put("status", DEFAULT_PROJECT_STATUS);
        query.put("cate_id", DEFAULT_PROJECT_CATE_ID);
        query.put("director", DEFAULT_PROJECT_DIRECTOR);
        query.put("director_uid", DEFAULT_PROJECT_DIRECTOR_UID);
        query.put("keywords", DEFAULT_PROJECT_KEYWORDS);
        return parseOptions(
                execute(EXPENSE_PROJECT_LOOKUP_TOOL_CODE, Map.of("query", Map.copyOf(query)), toolContext),
                List.of("id", "value", "project_id", "projectid"),
                List.of("title", "name", "label", "project_name", "projectname"));
    }

    /**
     * 查询用户列表。
     */
    public List<UserRecord> listUsers(@Nullable ToolContext toolContext) {
        return parseUsers(execute(EXPENSE_USER_LOOKUP_TOOL_CODE, Map.of(), toolContext));
    }

    /**
     * 查询报销审批流程。
     */
    public List<FlowNodeRecord> listFlowNodes(@Nullable ToolContext toolContext) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("check_name", CHECK_NAME);
        query.put("action_id", "0");
        query.put("flow_id", FLOW_LOOKUP_FLOW_ID);
        return parseFlowNodes(execute(
                APPROVAL_FLOW_NODES_LOOKUP_TOOL_CODE,
                Map.of("query", Map.copyOf(query)),
                toolContext));
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
        String normalizedName = normalizeComparisonText(userName);
        return listUsers(toolContext).stream()
                .filter(user -> normalizedName.equals(normalizeComparisonText(user.name()))
                        || normalizedName.equals(normalizeComparisonText(user.displayName())))
                .findFirst();
    }

    public Optional<OptionRecord> findExpenseCategoryByValueOrLabel(String rawValue, @Nullable ToolContext toolContext) {
        return matchOption(rawValue, listExpenseCategories(toolContext));
    }

    /**
     * 提交报销申请。
     */
    public AddResult addExpense(ExpenseAddRequest request, @Nullable ToolContext toolContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject_id", request.subjectId());
        body.put("code", request.code());
        body.put("expense_time", request.expenseTime());
        body.put("income_month", request.incomeMonth());
        body.put("project_id", request.projectId());
        body.put("ptname", request.applicantName());
        body.put("flow_id", Optional.ofNullable(request.flowId()).filter(StringUtils::hasText).orElse(DEFAULT_FLOW_ID));
        body.put("check_name", CHECK_NAME);
        body.put("check_uids", request.checkUids());
        body.put("check_copy_uids", Optional.ofNullable(request.checkCopyUids()).orElse(""));

        List<ExpenseAddDetail> details = request.details() != null ? request.details() : List.of();
        for (int index = 0; index < details.size(); index++) {
            ExpenseAddDetail detail = details.get(index);
            body.put("amount[" + index + "]", detail.amount().stripTrailingZeros().toPlainString());
            body.put("cate_id[" + index + "]", detail.cateId());
            body.put("remarks[" + index + "]", Optional.ofNullable(detail.remarks()).orElse(""));
            body.put("expense_id[" + index + "]", Optional.ofNullable(detail.expenseId())
                    .filter(StringUtils::hasText)
                    .orElse("0"));
        }

        ToolExecutor.ExecutionResult executionResult = execute(
                EXPENSE_ADD_TOOL_CODE,
                Map.of("body", Map.copyOf(body)),
                toolContext);
        String message = resolveMessage(executionResult).orElse("报销申请提交成功");
        String recordId = resolveRecordId(executionResult).orElse(null);
        return new AddResult(true, message, recordId, summarizePayload(executionResult.payload()));
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
        log.info("ExpenseToolMetaService#execute - toolCode={}, output={}", toolCode,
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

        String did = Optional.ofNullable(firstNonBlank(
                firstText(node, "did", "dept_id", "deptId", "department_id", "departmentId"),
                inheritedDid)).orElse("");
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
        String resolvedName = Optional.ofNullable(firstNonBlank(extractPureUserName(rawName), rawName)).orElse(rawName);
        return Optional.of(new UserRecord(id, resolvedName, did, departmentName));
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
                .or(() -> Optional.ofNullable(firstText(payloadNode.path("finalOutputs"), "return_id", "record_id", "recordId", "id", "aid")));
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
        String normalized = rawName.trim();
        String[] parts = normalized.split("\\s*-\\s*");
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

    public record AddResult(boolean success, String message, String recordId, String rawPayload) {
    }

    private record NameParts(String userName, String departmentName) {
    }
}
