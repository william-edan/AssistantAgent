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
package com.alibaba.assistant.agent.start.expense.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.expense.service.ExpenseToolMetaService;
import com.alibaba.assistant.agent.start.expense.util.ExpenseFormSummaryParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 报销申请表单工具。
 *
 * <p>职责：
 * 1) 通过 tool_meta 预加载主体、类型、项目、审批流、用户列表；
 * 2) 把自然语言里的报销信息尽量转成结构化表单值；
 * 3) 选择报销人后自动补齐部门；
 * 4) 返回前端可直接渲染的动态表单结构。</p>
 */
@Component
@Profile("migration")
public class ExpenseAddFormTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "expense_add_form";

    private static final String FLOW_REMOTE = "/api/check/get_flow_nodes?check_name=expense&action_id=0&flow_id=0";

    private static final Logger log = LoggerFactory.getLogger(ExpenseAddFormTool.class);

    private static final DateTimeFormatter CODE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ExpenseToolMetaService expenseToolMetaService;

    public ExpenseAddFormTool(ObjectMapper objectMapper, ExpenseToolMetaService expenseToolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.expenseToolMetaService = expenseToolMetaService;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        FieldOptions fieldOptions = loadFieldOptions(toolContext);
        Map<String, Object> values = resolveValues(args, toolContext, fieldOptions);

        List<Map<String, Object>> fields = buildFields(values, fieldOptions);
        List<Map<String, Object>> missingFields = resolveMissingFields(values, fields);

        boolean canSubmit = missingFields.isEmpty();
        String mode = canSubmit ? "CONFIRM" : "COLLECT";
        String phase = canSubmit ? "CONFIRMING" : "COLLECTING";
        String status = canSubmit ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
        String message = canSubmit ? "请确认报销申请信息后提交。" : "请补全报销申请信息。";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "FORM");
        payload.put("type", "form");
        payload.put("title", "新增报销申请");
        payload.put("toolCode", ExpenseAddTool.TOOL_NAME);
        payload.put("artifactCode", ExpenseAddTool.TOOL_NAME);
        payload.put("submit_tool", ExpenseAddTool.TOOL_NAME);
        payload.put("mode", mode);
        payload.put("phase", phase);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("values", values);
        payload.put("fields", fields);
        payload.put("missingFields", missingFields);
        payload.put("summary", Map.of());
        payload.put("canSubmit", canSubmit);
        return objectMapper.writeValueAsString(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveValues(
            Map<String, Object> args,
            @Nullable ToolContext toolContext,
            FieldOptions fieldOptions) {
        Map<String, Object> values = new LinkedHashMap<>();

        Object rawValues = args.get("values");
        if (rawValues instanceof Map<?, ?> valueMap) {
            valueMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }

        mergeSummaryValues(values, asText(args.get("userInput")), fieldOptions);

        Object rawSlotInputs = args.get("slotInputs");
        if (rawSlotInputs instanceof Map<?, ?> slotInputMap) {
            slotInputMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }

        args.forEach((key, value) -> {
            if (isBusinessField(key) && value != null) {
                values.put(key, value);
            }
        });

        applyDefaults(values, fieldOptions);
        normalizeValues(values, fieldOptions, toolContext);
        applyDefaults(values, fieldOptions);
        return values;
    }

    private void mergeSummaryValues(
            Map<String, Object> values,
            @Nullable String userInput,
            FieldOptions fieldOptions) {
        ExpenseFormSummaryParser.ParsedExpenseSummary summary = ExpenseFormSummaryParser.extract(userInput);
        putIfHasText(values, "code", summary.code());
        putIfHasText(values, "expense_time", summary.expenseTime());
        putIfHasText(values, "income_month", summary.incomeMonth());
        putIfHasText(values, "subject_id", summary.subjectId());
        putIfHasText(values, "project_id", summary.projectId());
        putIfHasText(values, "ptname", summary.applicant());
        putIfHasText(values, "flow_id", summary.flow());
        putIfHasText(values, "check_uids", summary.approver());
        putIfHasText(values, "check_copy_uids", summary.copyUsers());

        List<Map<String, Object>> summaryDetails = buildSummaryDetails(summary.details());
        boolean hasSummaryDetail = !summaryDetails.isEmpty();
        if (!hasSummaryDetail) {
            normalizeDetails(values, fieldOptions);
            return;
        }

        if (!hasMeaningfulDetails(values.get("details"))) {
            values.put("details", summaryDetails);
        }
        else {
            List<Map<String, Object>> details = toListOfMaps(values.get("details"));
            List<Map<String, Object>> mergedDetails = new ArrayList<>();
            int size = Math.max(details.size(), summaryDetails.size());
            for (int index = 0; index < size; index++) {
                Map<String, Object> detail = index < details.size()
                        ? new LinkedHashMap<>(details.get(index))
                        : new LinkedHashMap<>();
                Map<String, Object> summaryDetail = index < summaryDetails.size() ? summaryDetails.get(index) : Map.of();
                if (!StringUtils.hasText(asText(detail.get("cate_id")))) {
                    putIfHasText(detail, "cate_id", asText(summaryDetail.get("cate_id")));
                }
                if (!StringUtils.hasText(asText(detail.get("amount")))) {
                    putIfHasText(detail, "amount", asText(summaryDetail.get("amount")));
                }
                if (!StringUtils.hasText(asText(detail.get("remarks")))) {
                    putIfHasText(detail, "remarks", asText(summaryDetail.get("remarks")));
                }
                if (!StringUtils.hasText(asText(detail.get("expense_id")))) {
                    detail.put("expense_id", "0");
                }
                if (hasMeaningfulDetails(List.of(detail))) {
                    mergedDetails.add(detail);
                }
            }
            if (!mergedDetails.isEmpty()) {
                values.put("details", mergedDetails);
            }
        }

        normalizeDetails(values, fieldOptions);
    }

    private void applyDefaults(Map<String, Object> values, FieldOptions fieldOptions) {
        String expenseTime = asText(values.get("expense_time"));
        if (!StringUtils.hasText(expenseTime)) {
            expenseTime = LocalDate.now().toString();
            values.put("expense_time", expenseTime);
        }
        if (!StringUtils.hasText(asText(values.get("income_month")))) {
            values.put("income_month", expenseTime.length() >= 7 ? expenseTime.substring(0, 7) : LocalDate.now().toString().substring(0, 7));
        }
        if (!StringUtils.hasText(asText(values.get("code")))) {
            values.put("code", "BX" + LocalDateTime.now().format(CODE_FORMATTER));
        }
        // 需求临时约定：部门固定为 2。
        values.put("department", "2");
        values.put("check_name", ExpenseToolMetaService.CHECK_NAME);
        if (!hasValue(values.get("details"))) {
            values.put("details", new ArrayList<>(List.of(defaultDetail())));
        }
    }

    private void normalizeValues(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        normalizeOptionField(values, "subject_id", fieldOptions.subjectOptions());
        normalizeOptionField(values, "project_id", fieldOptions.projectOptions());
        normalizeOptionField(values, "flow_id", fieldOptions.flowOptions());
        normalizeApplicant(values, fieldOptions, toolContext);
        normalizeApprover(values, "check_uids", fieldOptions, toolContext, false);
        normalizeApprover(values, "check_copy_uids", fieldOptions, toolContext, false);
        normalizeDetails(values, fieldOptions);
    }

    private void normalizeOptionField(
            Map<String, Object> values,
            String fieldName,
            List<Map<String, Object>> options) {
        String rawValue = asText(values.get(fieldName));
        if (!StringUtils.hasText(rawValue)) {
            return;
        }
        String matchedValue = matchOptionValue(rawValue, options);
        if (StringUtils.hasText(matchedValue)) {
            values.put(fieldName, matchedValue);
        }
    }

    private void normalizeApplicant(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        String applicant = asText(values.get("ptname"));
        if (!StringUtils.hasText(applicant)) {
            return;
        }
        String matchedValue = matchOptionValue(applicant, fieldOptions.userOptions());
        if (StringUtils.hasText(matchedValue)) {
            applicant = matchedValue;
            values.put("ptname", matchedValue);
        }
        String resolvedApplicant = applicant;
        putIfHasText(values, "department", extractDepartmentName(resolvedApplicant));

        findUser(fieldOptions.users(), resolvedApplicant).ifPresent(user -> {
            values.put("ptname", user.id());
            values.put("ptname_name", firstText(extractUserName(user.name()), user.name()));
            putIfHasText(values, "department", firstText(
                    user.departmentName(),
                    extractDepartmentName(user.displayName()),
                    extractDepartmentName(user.name()),
                    extractDepartmentName(resolvedApplicant)));
        });
    }

    private void normalizeApprover(
            Map<String, Object> values,
            String fieldName,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext,
            boolean multiple) {
        List<String> normalizedIds = new ArrayList<>();
        for (String rawValue : toStringList(values.get(fieldName))) {
            String matchedValue = matchOptionValue(rawValue, fieldOptions.userOptions());
            if (StringUtils.hasText(matchedValue)) {
                rawValue = matchedValue;
            }
            String resolvedRawValue = rawValue;
            findUser(fieldOptions.users(), resolvedRawValue).ifPresent(user -> normalizedIds.add(user.id()));
        }

        if (normalizedIds.isEmpty()) {
            return;
        }
        if (multiple) {
            values.put(fieldName, normalizedIds);
        }
        else {
            values.put(fieldName, normalizedIds.get(0));
        }
    }

    private void normalizeDetails(Map<String, Object> values, FieldOptions fieldOptions) {
        List<Map<String, Object>> normalizedDetails = new ArrayList<>();
        for (Map<String, Object> rawDetail : toListOfMaps(values.get("details"))) {
            Map<String, Object> detail = new LinkedHashMap<>();
            String cateId = firstText(rawDetail.get("cate_id"), rawDetail.get("cate_name"));
            String matchedCateId = matchOptionValue(cateId, fieldOptions.expenseCategoryOptions());
            putIfHasText(detail, "cate_id", firstText(matchedCateId, cateId));
            putIfHasText(detail, "amount", normalizeAmountText(rawDetail.get("amount")));
            putIfHasText(detail, "remarks", firstText(rawDetail.get("remarks"), rawDetail.get("remark")));
            detail.put("expense_id", firstText(rawDetail.get("expense_id"), "0"));
            if (detail.size() > 1 || StringUtils.hasText(asText(detail.get("cate_id")))) {
                normalizedDetails.add(detail);
            }
        }
        if (normalizedDetails.isEmpty()) {
            normalizedDetails.add(defaultDetail());
        }
        values.put("details", normalizedDetails);
    }

    private String normalizeAmountText(Object rawAmount) {
        String text = asText(rawAmount);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        }
        catch (Exception ignored) {
            return text;
        }
    }

    private boolean isBusinessField(String key) {
        return StringUtils.hasText(key)
                && !"values".equals(key)
                && !"slotInputs".equals(key)
                && !"userInput".equals(key)
                && !"confirmed".equals(key)
                && !"frontendThreadState".equals(key);
    }

    private FieldOptions loadFieldOptions(@Nullable ToolContext toolContext) {
        List<Map<String, Object>> subjectOptions = loadSubjectOptions(toolContext);
        List<Map<String, Object>> expenseCategoryOptions = loadExpenseCategoryOptions(toolContext);
        List<Map<String, Object>> projectOptions = loadProjectOptions(toolContext);
        List<Map<String, Object>> flowOptions = loadFlowOptions(toolContext);
        List<ExpenseToolMetaService.UserRecord> users = loadUsers(toolContext);
        List<Map<String, Object>> userOptions = buildUserOptions(users);
        return new FieldOptions(
                subjectOptions,
                expenseCategoryOptions,
                projectOptions,
                flowOptions,
                users,
                userOptions);
    }

    private List<Map<String, Object>> loadSubjectOptions(@Nullable ToolContext toolContext) {
        try {
            return expenseToolMetaService.listSubjects(toolContext).stream()
                    .map(option -> option(option.label(), option.value()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn("ExpenseAddFormTool#loadSubjectOptions - preload failed, error={}", exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> loadExpenseCategoryOptions(@Nullable ToolContext toolContext) {
        try {
            return expenseToolMetaService.listExpenseCategories(toolContext).stream()
                    .map(option -> option(option.label(), option.value()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn("ExpenseAddFormTool#loadExpenseCategoryOptions - preload failed, error={}", exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> loadProjectOptions(@Nullable ToolContext toolContext) {
        try {
            return expenseToolMetaService.listProjects(toolContext).stream()
                    .map(option -> option(option.label(), option.value()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn("ExpenseAddFormTool#loadProjectOptions - preload failed, error={}", exception.getMessage());
            return List.of();
        }
    }

    private List<ExpenseToolMetaService.UserRecord> loadUsers(@Nullable ToolContext toolContext) {
        try {
            return expenseToolMetaService.listUsers(toolContext);
        }
        catch (Exception exception) {
            log.warn("ExpenseAddFormTool#loadUsers - preload failed, error={}", exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> buildUserOptions(List<ExpenseToolMetaService.UserRecord> users) {
        return users.stream()
                .map(user -> option(user.displayName(), user.id()))
                .toList();
    }

    private List<Map<String, Object>> loadFlowOptions(@Nullable ToolContext toolContext) {
        try {
            return expenseToolMetaService.listFlowNodes(toolContext).stream()
                    .map(flowNode -> option(flowNode.title(), flowNode.flowId()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn("ExpenseAddFormTool#loadFlowOptions - preload failed, error={}", exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> buildFields(Map<String, Object> values, FieldOptions fieldOptions) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("报销主体", "subject_id", "select", values.get("subject_id"), true, Map.of(
                "remote", "/home/cate/enterprise",
                "labelField", "title",
                "valueField", "id",
                "options", fieldOptions.subjectOptions(),
                "optionsLoaded", !fieldOptions.subjectOptions().isEmpty())));
        fields.add(field("凭证编号", "code", "input", values.get("code"), true, Map.of()));
        fields.add(field("报销日期", "expense_time", "date", values.get("expense_time"), true, Map.of()));
        fields.add(field("所属月份", "income_month", "month", values.get("income_month"), true, Map.of(
                "uiComponent", "month")));
        fields.add(field("关联项目", "project_id", "select", values.get("project_id"), true, Map.of(
                "remote", "/project/index/datalist?page=1&limit=20&status=&cate_id=&director=&director_uid=&keywords=",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.projectOptions(),
                "optionsLoaded", !fieldOptions.projectOptions().isEmpty())));
        fields.add(field("报销人", "ptname", "select", values.get("ptname"), true, Map.of(
                "remote", "/api/oa_integration/get_all_users",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions(),
                "optionsLoaded", !fieldOptions.userOptions().isEmpty())));
        fields.add(field("所属部门", "department", "input", values.get("department"), false, Map.of(
                "editable", false,
                "readOnly", true)));
        fields.add(buildDetailsField(values.get("details"), fieldOptions));
        fields.add(field("审批流程", "flow_id", "select", values.get("flow_id"), true, Map.of(
                "remote", FLOW_REMOTE,
                "labelField", "title",
                "valueField", "flow_id",
                "options", fieldOptions.flowOptions(),
                "optionsLoaded", !fieldOptions.flowOptions().isEmpty())));
        fields.add(field("审批人", "check_uids", "select", values.get("check_uids"), true, Map.of(
                "remote", "/api/oa_integration/get_all_users",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions(),
                "optionsLoaded", !fieldOptions.userOptions().isEmpty())));
        fields.add(field("抄送人", "check_copy_uids", "select", values.get("check_copy_uids"), false, Map.of(
                "remote", "/api/oa_integration/get_all_users",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions(),
                "optionsLoaded", !fieldOptions.userOptions().isEmpty())));
        return fields;
    }

    private Map<String, Object> buildDetailsField(Object value, FieldOptions fieldOptions) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("uiComponent", "expense_details");
        extra.put("allowAdd", true);
        extra.put("allowRemove", true);
        extra.put("minItems", 1);
        extra.put("addButtonText", "添加报销项");
        extra.put("itemFields", List.of(
                field("报销类型", "cate_id", "select", null, true, Map.of(
                        "remote", "/finance/expensecate/datalist",
                        "labelField", "title",
                        "valueField", "id",
                        "options", fieldOptions.expenseCategoryOptions(),
                        "optionsLoaded", !fieldOptions.expenseCategoryOptions().isEmpty())),
                field("金额", "amount", "number", null, true, Map.of()),
                field("备注", "remarks", "textarea", null, false, Map.of()),
                field("expense_id", "expense_id", "hidden", null, false, Map.of(
                        "editable", false,
                        "displayConfig", Map.of("showInSummary", false)))));
        extra.put("defaultItem", defaultDetail());
        return field("报销明细", "details", "array", value, true, extra);
    }

    private List<Map<String, Object>> resolveMissingFields(
            Map<String, Object> values,
            List<Map<String, Object>> fields) {
        List<Map<String, Object>> missingFields = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            if (!Boolean.TRUE.equals(field.get("required"))) {
                continue;
            }
            String name = asText(field.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if ("details".equals(name)) {
                if (!hasCompleteDetails(values.get("details"))) {
                    missingFields.add(Map.of("name", name, "title", firstText(field.get("title"), field.get("label"), name)));
                }
                continue;
            }
            if (!hasValue(values.get(name))) {
                missingFields.add(Map.of("name", name, "title", firstText(field.get("title"), field.get("label"), name)));
            }
        }
        return missingFields;
    }

    private boolean hasCompleteDetails(Object rawDetails) {
        List<Map<String, Object>> details = toListOfMaps(rawDetails);
        if (details.isEmpty()) {
            return false;
        }
        return details.stream().allMatch(detail ->
                StringUtils.hasText(asText(detail.get("cate_id")))
                        && StringUtils.hasText(asText(detail.get("amount"))));
    }

    private Map<String, Object> field(
            String label,
            String name,
            String type,
            Object value,
            boolean required,
            Map<String, Object> extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("label", label);
        field.put("title", label);
        field.put("name", name);
        field.put("type", type);
        field.put("value", value);
        field.put("required", required);
        if (extra != null && !extra.isEmpty()) {
            field.putAll(extra);
        }
        return field;
    }

    private Map<String, Object> option(String label, String value) {
        return Map.of("label", label, "value", value);
    }

    private Map<String, Object> defaultDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cate_id", "");
        detail.put("amount", "");
        detail.put("remarks", "");
        detail.put("expense_id", "0");
        return detail;
    }

    private String matchOptionValue(@Nullable String rawValue, List<Map<String, Object>> options) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String normalizedRawValue = normalizeComparisonText(rawValue);
        for (Map<String, Object> option : options) {
            String valueText = asText(option.get("value"));
            String labelText = firstText(option.get("label"), option.get("title"), option.get("name"));
            String normalizedValueText = normalizeComparisonText(valueText);
            String normalizedLabelText = normalizeComparisonText(labelText);
            if (normalizedRawValue.equals(normalizedValueText)
                    || normalizedRawValue.equals(normalizedLabelText)
                    || (StringUtils.hasText(normalizedLabelText) && normalizedRawValue.contains(normalizedLabelText))
                    || (StringUtils.hasText(normalizedValueText) && normalizedRawValue.contains(normalizedValueText))) {
                return valueText;
            }
        }
        return null;
    }

    private String normalizeComparisonText(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    // 兼容“姓名 - 部门”展示值，自动拆分出真实姓名与部门名称。
    private String extractUserName(@Nullable String displayText) {
        String[] parts = splitDisplayText(displayText);
        return parts != null ? parts[0] : null;
    }

    private String extractDepartmentName(@Nullable String displayText) {
        String[] parts = splitDisplayText(displayText);
        return parts != null ? parts[1] : null;
    }

    private String[] splitDisplayText(@Nullable String displayText) {
        if (!StringUtils.hasText(displayText)) {
            return null;
        }
        String[] parts = displayText.trim().split("\\s*-\\s*");
        if (parts.length < 2) {
            return null;
        }
        String userName = asText(parts[0]);
        String departmentName = asText(parts[parts.length - 1]);
        if (!StringUtils.hasText(userName) || !StringUtils.hasText(departmentName)) {
            return null;
        }
        return new String[] {userName, departmentName};
    }

    private Optional<ExpenseToolMetaService.UserRecord> findUser(
            List<ExpenseToolMetaService.UserRecord> users,
            @Nullable String rawValue) {
        if (!StringUtils.hasText(rawValue) || users == null || users.isEmpty()) {
            return Optional.empty();
        }
        String normalizedRawValue = normalizeComparisonText(rawValue);
        return users.stream()
                .filter(user -> normalizedRawValue.equals(normalizeComparisonText(user.id()))
                        || normalizedRawValue.equals(normalizeComparisonText(user.name()))
                        || normalizedRawValue.equals(normalizeComparisonText(user.displayName())))
                .findFirst();
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    // 将自然语言解析结果转换为表单可直接消费的明细结构。
    private List<Map<String, Object>> buildSummaryDetails(
            List<ExpenseFormSummaryParser.DetailSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (ExpenseFormSummaryParser.DetailSummary summary : summaries) {
            Map<String, Object> detail = new LinkedHashMap<>();
            putIfHasText(detail, "cate_id", summary.category());
            putIfHasText(detail, "amount", summary.amount());
            putIfHasText(detail, "remarks", summary.remarks());
            detail.put("expense_id", "0");
            if (StringUtils.hasText(asText(detail.get("cate_id")))
                    || StringUtils.hasText(asText(detail.get("amount")))
                    || StringUtils.hasText(asText(detail.get("remarks")))) {
                details.add(detail);
            }
        }
        return details;
    }

    // 只要存在一条填写过的明细，就认为当前 details 有效，避免覆盖前端已录入内容。
    private boolean hasMeaningfulDetails(Object rawDetails) {
        List<Map<String, Object>> details = toListOfMaps(rawDetails);
        if (details.isEmpty()) {
            return false;
        }
        return details.stream().anyMatch(detail ->
                StringUtils.hasText(asText(detail.get("cate_id")))
                        || StringUtils.hasText(asText(detail.get("amount")))
                        || StringUtils.hasText(asText(detail.get("remarks"))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String text = asText(item);
                if (StringUtils.hasText(text)) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return List.of(text.split("[,，、\\s]+"));
    }

    private void putIfHasText(Map<String, Object> values, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value);
        }
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Return the expense-application form schema for frontend rendering.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "userInput": {
                              "type": "string",
                              "description": "Original user input"
                            },
                            "values": {
                              "type": "object",
                              "description": "Pre-filled form values"
                            },
                            "slotInputs": {
                              "type": "object",
                              "description": "Current structured slot inputs"
                            }
                          }
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("expense_add_tools")
                .targetClassDescription("Expense add form tools")
                .fewShots(List.of(new CodeExample(
                        "open expense add form",
                        "result = expense_add_form(userInput='我要报销交通费')",
                        "返回报销申请表单")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }

    private record FieldOptions(
            List<Map<String, Object>> subjectOptions,
            List<Map<String, Object>> expenseCategoryOptions,
            List<Map<String, Object>> projectOptions,
            List<Map<String, Object>> flowOptions,
            List<ExpenseToolMetaService.UserRecord> users,
            List<Map<String, Object>> userOptions) {
    }
}
