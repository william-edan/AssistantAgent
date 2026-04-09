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
package com.alibaba.assistant.agent.start.seal.service;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.seal.model.SealApplyRequest;
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
import java.util.stream.Stream;

/**
 * Seal-apply dependency service.
 *
 * <p>All real business APIs are invoked through tool_meta to avoid direct HTTP in local tools.</p>
 */
@Service
@Profile("migration")
public class SealToolMetaService {

    private static final Logger log = LoggerFactory.getLogger(SealToolMetaService.class);

    private static final String DEFAULT_TENANT = "default";

    public static final String SEAL_CATEGORY_LOOKUP_TOOL_CODE = "gougu_oa.seal_category_lookup";

    public static final String SEAL_USER_LOOKUP_TOOL_CODE = "gougu_oa.seal_user_lookup";

    public static final String APPROVER_CANDIDATES_TOOL_CODE = "gougu_oa.approver_candidates";

    public static final String SEAL_APPLY_ADD_TOOL_CODE = "gougu_oa.seal_apply_add";

    public static final String SEAL_APPLY_SUBMIT_TOOL_CODE = "gougu_oa.seal_apply_submit";

    public static final String APPROVAL_FLOW_NODES_LOOKUP_TOOL_CODE = "gougu_oa.approval_flow_nodes_lookup";

    private static final String DEFAULT_FLOW_CHECK_NAME = "seal";

    private static final String DEFAULT_FLOW_ACTION_ID = "0";

    private static final String DEFAULT_FLOW_ID = "0";

    private static final List<String> SEAL_FLOW_CHECK_NAME_CANDIDATES = List.of(
            "seal",
            "seal_order",
            "seal_apply",
            "seals");

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

    public SealToolMetaService(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * Query seal category options.
     */
    public List<SealCategoryRecord> listSealCategories(@Nullable ToolContext toolContext) {
        return parseSealCategories(execute(SEAL_CATEGORY_LOOKUP_TOOL_CODE, Map.of(), toolContext));
    }

    /**
     * Query approver/copy candidates and merge department-aware data.
     *
     * <p>Use V28 approver-candidates (department tree) first, then merge with full user directory.</p>
     */
    public List<UserRecord> listUsers(@Nullable ToolContext toolContext) {
        List<UserRecord> approverUsers = safeListUsers(APPROVER_CANDIDATES_TOOL_CODE, toolContext);
        List<UserRecord> directoryUsers = safeListUsers(SEAL_USER_LOOKUP_TOOL_CODE, toolContext);

        if (approverUsers.isEmpty()) {
            return List.copyOf(directoryUsers);
        }
        if (directoryUsers.isEmpty()) {
            return List.copyOf(approverUsers);
        }

        Map<String, UserRecord> mergedUsers = new LinkedHashMap<>();
        approverUsers.forEach(user -> mergedUsers.put(user.id(), user));
        directoryUsers.forEach(user -> mergedUsers.merge(user.id(), user, this::mergeUserRecord));
        return List.copyOf(mergedUsers.values());
    }

    /**
     * Query department options directly from approver-candidates (department tree).
     */
    public List<DepartmentRecord> listDepartments(@Nullable ToolContext toolContext) {
        try {
            return parseDepartments(execute(APPROVER_CANDIDATES_TOOL_CODE, Map.of(), toolContext));
        }
        catch (RuntimeException exception) {
            log.warn(
                    "SealToolMetaService#listDepartments - lookup failed, toolCode={}, error={}",
                    APPROVER_CANDIDATES_TOOL_CODE,
                    exception.getMessage());
            Map<String, DepartmentRecord> fallback = new LinkedHashMap<>();
            listUsers(toolContext).forEach(user -> {
                if (StringUtils.hasText(user.did()) && StringUtils.hasText(user.departmentName())) {
                    fallback.putIfAbsent(user.did(), new DepartmentRecord(user.did(), user.departmentName()));
                }
            });
            return List.copyOf(fallback.values());
        }
    }

    /**
     * Query approval-flow nodes (generic).
     */
    public List<FlowNodeRecord> listFlowNodes(
            String checkName,
            @Nullable String actionId,
            @Nullable String flowId,
            @Nullable ToolContext toolContext) {
        String resolvedCheckName = Optional.ofNullable(checkName)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_FLOW_CHECK_NAME);
        String resolvedActionId = Optional.ofNullable(actionId)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_FLOW_ACTION_ID);
        String resolvedFlowId = Optional.ofNullable(flowId)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_FLOW_ID);

        RuntimeException lastException = null;
        for (String candidateCheckName : resolveFlowCheckNameCandidates(resolvedCheckName)) {
            try {
                List<FlowNodeRecord> flowNodes = parseFlowNodes(execute(
                        APPROVAL_FLOW_NODES_LOOKUP_TOOL_CODE,
                        buildFlowNodeLookupArguments(candidateCheckName, resolvedActionId, resolvedFlowId),
                        toolContext));
                if (!flowNodes.isEmpty()) {
                    return flowNodes;
                }
            }
            catch (RuntimeException exception) {
                lastException = exception;
                log.warn(
                        "SealToolMetaService#listFlowNodes - lookup failed, checkName={}, actionId={}, flowId={}, error={}",
                        candidateCheckName,
                        resolvedActionId,
                        resolvedFlowId,
                        exception.getMessage());
            }
        }

        if (lastException != null) {
            log.warn(
                    "SealToolMetaService#listFlowNodes - all candidates failed, checkName={}, actionId={}, flowId={}, returning empty",
                    resolvedCheckName,
                    resolvedActionId,
                    resolvedFlowId);
        }
        return List.of();
    }

    /**
     * Find user by ID.
     */
    public Optional<UserRecord> findUserById(String userId, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        return listUsers(toolContext).stream()
                .filter(user -> userId.trim().equals(user.id()))
                .findFirst();
    }

    /**
     * Find user by name or display-name.
     */
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

    /**
     * Find seal category by title.
     */
    public Optional<SealCategoryRecord> findSealCategoryByTitle(String title, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(title)) {
            return Optional.empty();
        }
        String normalizedTitle = normalizeComparisonText(title);
        return listSealCategories(toolContext).stream()
                .filter(record -> normalizedTitle.equals(normalizeComparisonText(record.title())))
                .findFirst();
    }

    /**
     * Find flow node by title.
     */
    public Optional<FlowNodeRecord> findFlowNodeByTitle(
            String checkName,
            String title,
            @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(title)) {
            return Optional.empty();
        }
        String normalizedTitle = normalizeComparisonText(title);
        return listFlowNodes(checkName, DEFAULT_FLOW_ACTION_ID, DEFAULT_FLOW_ID, toolContext).stream()
                .filter(record -> normalizedTitle.equals(normalizeComparisonText(record.title())))
                .findFirst();
    }

    /**
     * Find department name by department ID.
     */
    public Optional<String> findDepartmentNameById(String did, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(did)) {
            return Optional.empty();
        }
        return listUsers(toolContext).stream()
                .filter(user -> did.trim().equals(user.did()))
                .map(UserRecord::departmentName)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    /**
     * Find department ID by department name.
     */
    public Optional<String> findDepartmentIdByName(String departmentName, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(departmentName)) {
            return Optional.empty();
        }
        String normalizedDepartment = normalizeComparisonText(departmentName);
        return listUsers(toolContext).stream()
                .filter(user -> StringUtils.hasText(user.departmentName()))
                .filter(user -> normalizedDepartment.equals(normalizeComparisonText(user.departmentName())))
                .map(UserRecord::did)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    /**
     * Create seal record first, then submit approval.
     */
    public SubmitResult createAndSubmit(SealApplyRequest request, @Nullable ToolContext toolContext) {
        ToolExecutor.ExecutionResult addResult = execute(
                SEAL_APPLY_ADD_TOOL_CODE,
                buildAddArguments(request),
                toolContext);
        String recordId = resolveRecordId(addResult)
                .orElseThrow(() -> new IllegalStateException("新增用章申请失败：接口未返回 return_id"));

        ToolExecutor.ExecutionResult submitResult = submitApprovalWithCheckNameFallback(recordId, request, toolContext);

        String message = resolveMessage(submitResult)
                .or(() -> resolveMessage(addResult))
                .orElse("用章申请提交成功");
        return new SubmitResult(true, message, recordId, summarizePayload(submitResult.payload()));
    }

    private List<UserRecord> safeListUsers(String toolCode, @Nullable ToolContext toolContext) {
        try {
            return parseUsers(execute(toolCode, Map.of(), toolContext));
        }
        catch (RuntimeException exception) {
            log.warn(
                    "SealToolMetaService#safeListUsers - lookup failed, toolCode={}, error={}",
                    toolCode,
                    exception.getMessage());
            return List.of();
        }
    }

    private UserRecord mergeUserRecord(UserRecord preferred, UserRecord incoming) {
        String id = Optional.ofNullable(firstNonBlank(preferred.id(), incoming.id())).orElse("");
        String name = Optional.ofNullable(firstNonBlank(incoming.name(), preferred.name())).orElse("");
        String did = Optional.ofNullable(firstNonBlank(preferred.did(), incoming.did())).orElse("");
        String departmentName = Optional.ofNullable(firstNonBlank(preferred.departmentName(), incoming.departmentName())).orElse("");
        return new UserRecord(id, name, did, departmentName);
    }

    private ToolExecutor.ExecutionResult submitApprovalWithCheckNameFallback(
            String actionId,
            SealApplyRequest request,
            @Nullable ToolContext toolContext) {
        String flowId = Optional.ofNullable(request.flowId()).filter(StringUtils::hasText).orElse("0");
        String checkUids = Optional.ofNullable(request.checkUids()).orElse("");
        String checkCopyUids = Optional.ofNullable(request.checkCopyUids()).orElse("");

        RuntimeException lastException = null;
        for (String checkName : resolveFlowCheckNameCandidates(DEFAULT_FLOW_CHECK_NAME)) {
            try {
                return execute(
                        SEAL_APPLY_SUBMIT_TOOL_CODE,
                        Map.of(
                                "action_id", actionId,
                                "check_name", checkName,
                                "flow_id", flowId,
                                "check_uids", checkUids,
                                "check_copy_uids", checkCopyUids),
                        toolContext);
            }
            catch (RuntimeException exception) {
                lastException = exception;
                log.warn(
                        "SealToolMetaService#submitApprovalWithCheckNameFallback - submit failed, checkName={}, actionId={}, flowId={}, checkUids={}, error={}",
                        checkName,
                        actionId,
                        flowId,
                        checkUids,
                        exception.getMessage());
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalStateException("提交审批失败：未命中可用 check_name");
    }

    private Map<String, Object> buildAddArguments(SealApplyRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("title", Optional.ofNullable(request.title()).orElse(""));
        arguments.put("did", Optional.ofNullable(request.did()).orElse("2"));
        arguments.put("num", Optional.ofNullable(request.num()).orElse("1"));
        arguments.put("use_time", Optional.ofNullable(request.useTime()).orElse(""));
        arguments.put("seal_cate_id", Optional.ofNullable(request.sealCateId()).orElse(""));
        arguments.put("is_borrow", Optional.ofNullable(request.isBorrow()).orElse("0"));
        arguments.put("start_time", Optional.ofNullable(request.startTime()).orElse(""));
        arguments.put("end_time", Optional.ofNullable(request.endTime()).orElse(""));
        arguments.put("content", Optional.ofNullable(request.content()).orElse(""));
        arguments.put("file", Optional.ofNullable(request.file()).orElse(""));
        arguments.put("file_ids", Optional.ofNullable(request.fileIds()).orElse(""));
        arguments.put("flow_id", Optional.ofNullable(request.flowId()).orElse("5"));
        arguments.put("check_uames", Optional.ofNullable(request.checkUnames()).orElse(""));
        arguments.put("check_uids", Optional.ofNullable(request.checkUids()).orElse(""));
        arguments.put("check_copy_unames", Optional.ofNullable(request.checkCopyUnames()).orElse(""));
        arguments.put("check_copy_uids", Optional.ofNullable(request.checkCopyUids()).orElse(""));
        return Map.copyOf(arguments);
    }

    private Map<String, Object> buildFlowNodeLookupArguments(String checkName, String actionId, String flowId) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("check_name", checkName);
        query.put("action_id", actionId);
        query.put("flow_id", flowId);
        return Map.of("query", Map.copyOf(query));
    }

    private List<String> resolveFlowCheckNameCandidates(String checkName) {
        if (!isSealWorkflowCheckName(checkName)) {
            return List.of(checkName);
        }
        return Stream.concat(
                        Stream.of(checkName),
                        SEAL_FLOW_CHECK_NAME_CANDIDATES.stream())
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean isSealWorkflowCheckName(String checkName) {
        if (!StringUtils.hasText(checkName)) {
            return false;
        }
        String normalized = checkName.trim().toLowerCase(Locale.ROOT);
        return SEAL_FLOW_CHECK_NAME_CANDIDATES.stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(normalized));
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
        log.info(
                "SealToolMetaService#execute - toolCode={}, output={}",
                toolCode,
                summarizePayload(executionResult.outputFields()));
        return executionResult;
    }

    private List<SealCategoryRecord> parseSealCategories(ToolExecutor.ExecutionResult executionResult) {
        List<SealCategoryRecord> categories = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String id = firstText(node, "id", "value", "cate_id", "seal_cate_id");
            String title = firstText(node, "title", "name", "label", "cate_name");
            if (StringUtils.hasText(id) && StringUtils.hasText(title)) {
                categories.add(new SealCategoryRecord(id, title));
            }
        }
        return List.copyOf(categories);
    }

    private List<UserRecord> parseUsers(ToolExecutor.ExecutionResult executionResult) {
        Map<String, UserRecord> users = new LinkedHashMap<>();
        collectUsers(resolveDataNode(executionResult), null, null, users);
        return List.copyOf(users.values());
    }

    private List<DepartmentRecord> parseDepartments(ToolExecutor.ExecutionResult executionResult) {
        Map<String, DepartmentRecord> departments = new LinkedHashMap<>();
        collectDepartments(resolveDataNode(executionResult), null, null, departments);
        return List.copyOf(departments.values());
    }

    private void collectDepartments(
            JsonNode node,
            @Nullable String inheritedDid,
            @Nullable String inheritedDepartmentName,
            Map<String, DepartmentRecord> departments) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectDepartments(item, inheritedDid, inheritedDepartmentName, departments));
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
        if (departmentNode && StringUtils.hasText(nextDid) && StringUtils.hasText(nextDepartmentName)) {
            departments.putIfAbsent(nextDid, new DepartmentRecord(nextDid, nextDepartmentName));
        }

        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode child = field.getValue();
            if (child != null && (child.isArray() || child.isObject())) {
                collectDepartments(child, nextDid, nextDepartmentName, departments);
            }
        }
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

        toUserRecord(node, nextDid, nextDepartmentName).ifPresent(user ->
                users.merge(user.id(), user, this::mergeUserRecord));

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
        String name = firstText(node, "name", "realname", "real_name", "uname", "nick_name", "nickName", "label", "title");
        if (!StringUtils.hasText(id) || !StringUtils.hasText(name)) {
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
                inheritedDepartmentName)).orElse("");

        return Optional.of(new UserRecord(id, name, did, departmentName));
    }

    private boolean looksLikeUserNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        // Explicit user marker fields.
        if (StringUtils.hasText(firstText(node, "uid", "user_id", "userId", "employee_id", "employeeId", "realname", "real_name", "uname"))) {
            return true;
        }
        // Generic id/name object without child collections can also be a user.
        boolean hasId = StringUtils.hasText(firstText(node, "id"));
        boolean hasName = StringUtils.hasText(firstText(node, "name", "realname", "real_name", "uname", "nick_name", "nickName", "label", "title"));
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
            String resolvedFlowId = firstText(node, "flow_id", "id");
            String checkType = firstText(node, "check_type", "checkType");
            if (StringUtils.hasText(resolvedFlowId) && StringUtils.hasText(title)) {
                flowNodes.add(new FlowNodeRecord(
                        Optional.ofNullable(id).orElse(resolvedFlowId),
                        title,
                        resolvedFlowId,
                        checkType));
            }
        }
        return List.copyOf(flowNodes);
    }

    private Optional<String> resolveMessage(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        return Optional.ofNullable(firstText(
                outputNode,
                "msg",
                "message",
                "result_message",
                "resultMessage"))
                .or(() -> Optional.ofNullable(firstText(
                        payloadNode.path("finalOutputs"),
                        "message",
                        "msg")))
                .or(() -> Optional.ofNullable(firstText(payloadNode, "message", "error")));
    }

    private Optional<String> resolveRecordId(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        JsonNode dataNode = resolveDataNode(executionResult);
        return Optional.ofNullable(firstText(
                dataNode,
                "return_id",
                "record_id",
                "recordId",
                "id"))
                .or(() -> Optional.ofNullable(firstText(
                        outputNode,
                        "return_id",
                        "record_id",
                        "recordId",
                        "id")))
                .or(() -> Optional.ofNullable(firstText(
                        payloadNode.path("finalOutputs"),
                        "return_id",
                        "record_id",
                        "recordId",
                        "id")));
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

    public record SealCategoryRecord(String id, String title) {
    }

    public record UserRecord(String id, String name, String did, String departmentName) {

        public String displayName() {
            if (StringUtils.hasText(departmentName)) {
                return name + " - " + departmentName;
            }
            return name;
        }
    }

    public record DepartmentRecord(String id, String name) {
    }

    public record SubmitResult(boolean success, String message, String recordId, String rawPayload) {
    }

    public record FlowNodeRecord(String id, String title, String flowId, String checkType) {
    }
}
