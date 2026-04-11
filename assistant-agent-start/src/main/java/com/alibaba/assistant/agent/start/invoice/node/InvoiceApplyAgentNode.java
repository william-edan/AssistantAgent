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
package com.alibaba.assistant.agent.start.invoice.node;

import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.invoice.model.InvoiceApplyRequest;
import com.alibaba.assistant.agent.start.invoice.service.InvoiceToolMetaService;
import com.alibaba.assistant.agent.start.invoice.tool.InvoiceNaturalLanguageSlotParser;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 开票申请 Agent 节点。
 *
 * <p>职责：
 * 1. 将自然语言和前端回填值合并为结构化表单；
 * 2. 根据 {@code types} 做企业/个人字段联动；
 * 3. 提交前自动补齐 {@code flow_id/check_uids/check_copy_uids}；
 * 4. 后端最终统一调用 tool_meta 完成 {@code /finance/invoice/add -> /api/check/submit_check} 两段式提交。</p>
 */
@Component
@Profile("migration")
public class InvoiceApplyAgentNode {

    private static final String FORM_TITLE = "新增开票申请";

    private static final String SUBMIT_TOOL_CODE = "invoice_apply";

    private static final String SUBJECT_REMOTE = "/home/cate/enterprise";

    private static final String CUSTOMER_REMOTE = "/customer/customer/datalist?page=1&limit=20&keywords=";

    private static final String PROJECT_REMOTE =
            "/project/index/datalist?page=1&limit=20&status=&cate_id=&director=&director_uid=&keywords=";

    private static final String USER_REMOTE = "/api/oa_integration/get_all_users";

    private static final String FLOW_REMOTE = "/api/check/get_flow_nodes?check_name=expense&action_id=0&flow_id=0";

    private static final Map<String, String> FIELD_TITLES = Map.ofEntries(
            Map.entry("amount", "开票金额"),
            Map.entry("invoice_type", "发票类型"),
            Map.entry("invoice_subject", "开票主体"),
            Map.entry("types", "抬头类型"),
            Map.entry("invoice_title", "开票抬头"),
            Map.entry("invoice_tax", "纳税识别号"),
            Map.entry("invoice_bank", "开户行"),
            Map.entry("invoice_account", "银行账号"),
            Map.entry("invoice_banking", "银行营业点"),
            Map.entry("invoice_phone", "电话"),
            Map.entry("invoice_address", "地址"),
            Map.entry("project_id", "关联项目"),
            Map.entry("remark", "备注"),
            Map.entry("flow_id", "审批流程"),
            Map.entry("check_uids", "审批人"),
            Map.entry("check_copy_uids", "抄送人"));

    private static final List<Map<String, Object>> INVOICE_TYPE_OPTIONS = List.of(
            Map.<String, Object>of("label", "增值税专用发票", "value", "1"),
            Map.<String, Object>of("label", "普通发票", "value", "2"),
            Map.<String, Object>of("label", "专用发票", "value", "3"));

    private static final List<Map<String, Object>> TITLE_TYPE_OPTIONS = List.of(
            Map.<String, Object>of("label", "企业", "value", "1"),
            Map.<String, Object>of("label", "个人", "value", "2"));

    private final InvoiceToolMetaService invoiceToolMetaService;

    public InvoiceApplyAgentNode(InvoiceToolMetaService invoiceToolMetaService) {
        this.invoiceToolMetaService = invoiceToolMetaService;
    }

    /**
     * 构建前端表单。
     */
    public Map<String, Object> buildForm(Map<String, Object> args, @Nullable ToolContext toolContext) {
        ResolvedContext resolvedContext = resolveContext(args, toolContext, false);
        Map<String, Object> values = resolvedContext.values();
        List<Map<String, Object>> fields = buildFields(values, resolvedContext.fieldOptions());
        List<Map<String, Object>> missingFields = resolveMissingFields(values, fields);

        boolean canSubmit = missingFields.isEmpty();
        String mode = canSubmit ? "CONFIRM" : "COLLECT";
        String phase = canSubmit ? "CONFIRMING" : "COLLECTING";
        String status = canSubmit ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
        String message = canSubmit ? "请确认开票申请信息后提交。" : "请补全开票申请信息。";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "FORM");
        payload.put("type", "form");
        payload.put("title", FORM_TITLE);
        payload.put("toolCode", SUBMIT_TOOL_CODE);
        payload.put("artifactCode", SUBMIT_TOOL_CODE);
        payload.put("submit_tool", SUBMIT_TOOL_CODE);
        payload.put("mode", mode);
        payload.put("phase", phase);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("values", values);
        payload.put("fields", fields);
        payload.put("missingFields", missingFields);
        payload.put("summary", Map.of());
        payload.put("canSubmit", canSubmit);
        return payload;
    }

    /**
     * 提交开票申请。
     */
    public Map<String, Object> submit(Map<String, Object> args, @Nullable ToolContext toolContext) {
        try {
            ResolvedContext resolvedContext = resolveContext(args, toolContext, true);
            Map<String, Object> values = resolvedContext.values();
            List<Map<String, Object>> missingFields = resolveMissingFields(
                    values,
                    buildFields(values, resolvedContext.fieldOptions()));
            if (!missingFields.isEmpty()) {
                return errorPayload("请补全必要字段后再提交。", values, missingFields);
            }

            BigDecimal amount = parsePositiveAmount(values.get("amount"));
            if (amount == null) {
                return errorPayload("开票金额必须大于 0。", values, List.of(fieldRef("amount")));
            }

            InvoiceApplyRequest request = new InvoiceApplyRequest(
                    amount.stripTrailingZeros().toPlainString(),
                    asText(values.get("invoice_type")),
                    asText(values.get("invoice_subject")),
                    asText(values.get("types")),
                    asText(values.get("invoice_title")),
                    asText(values.get("invoice_tax")),
                    asText(values.get("invoice_bank")),
                    asText(values.get("invoice_account")),
                    asText(values.get("invoice_banking")),
                    asText(values.get("invoice_phone")),
                    asText(values.get("invoice_address")),
                    asText(values.get("project_name")),
                    asText(values.get("project_id")),
                    asText(values.get("remark")),
                    asText(values.get("flow_id")),
                    asText(values.get("check_uids")),
                    asText(values.get("check_uames")),
                    asText(values.get("check_copy_uids")),
                    asText(values.get("check_copy_unames")));

            InvoiceToolMetaService.AddResult result = invoiceToolMetaService.createAndSubmitInvoice(request, toolContext);
            return successPayload(result, request);
        }
        catch (Exception exception) {
            String message = Optional.ofNullable(exception.getMessage())
                    .filter(StringUtils::hasText)
                    .orElse("开票申请提交失败");
            return errorPayload(message, Map.of(), List.of());
        }
    }

    private ResolvedContext resolveContext(
            Map<String, Object> args,
            @Nullable ToolContext toolContext,
            boolean mergePendingForm) {
        Map<String, Object> values = resolveValues(args, toolContext, mergePendingForm);
        FieldOptions fieldOptions = loadFieldOptions(toolContext, values);
        applyDefaults(values, fieldOptions);
        normalizeValues(values, fieldOptions, toolContext);
        applyDefaults(values, fieldOptions);
        clearIrrelevantFields(values);
        return new ResolvedContext(values, fieldOptions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveValues(
            Map<String, Object> args,
            @Nullable ToolContext toolContext,
            boolean mergePendingForm) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (mergePendingForm) {
            Object frontendThreadState = args.get("frontendThreadState");
            if (frontendThreadState instanceof Map<?, ?> stateMap) {
                Object pendingForm = stateMap.get("pendingForm");
                if (pendingForm instanceof Map<?, ?> pendingFormMap
                        && SUBMIT_TOOL_CODE.equals(asText(pendingFormMap.get("toolCode")))) {
                    mergeMap(values, pendingFormMap.get("values"));
                }
            }
        }

        mergeMap(values, args.get("values"));
        values.putAll(InvoiceNaturalLanguageSlotParser.parse(asText(args.get("userInput"))));
        mergeMap(values, args.get("slotInputs"));

        args.forEach((key, value) -> {
            if (isBusinessField(key) && value != null) {
                values.put(key, value);
            }
        });

        OverAllState state = extractState(toolContext);
        if (state != null) {
            Object currentTurnInputs = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
            mergeMap(values, currentTurnInputs);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private void mergeMap(Map<String, Object> target, Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> mapValue)) {
            return;
        }
        mapValue.forEach((key, value) -> {
            if (key != null && value != null) {
                target.put(String.valueOf(key), value);
            }
        });
    }

    private FieldOptions loadFieldOptions(@Nullable ToolContext toolContext, Map<String, Object> values) {
        List<Map<String, Object>> subjectOptions = loadSubjectOptions(toolContext);
        List<Map<String, Object>> projectOptions = loadProjectOptions(toolContext);
        List<Map<String, Object>> flowOptions = loadFlowOptions(toolContext);
        List<InvoiceToolMetaService.UserRecord> users = loadUsers(toolContext);
        List<Map<String, Object>> userOptions = users.stream()
                .map(user -> option(user.displayName(), user.id()))
                .toList();

        List<InvoiceToolMetaService.CustomerRecord> customers = loadCustomers(values, toolContext);
        List<Map<String, Object>> customerOptions = customers.stream()
                .map(customer -> option(customer.name(), customer.name()))
                .toList();
        return new FieldOptions(subjectOptions, projectOptions, flowOptions, users, userOptions, customers, customerOptions);
    }

    private List<Map<String, Object>> loadSubjectOptions(@Nullable ToolContext toolContext) {
        try {
            return invoiceToolMetaService.listInvoiceSubjects(toolContext).stream()
                    .map(option -> option(option.label(), option.value()))
                    .toList();
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadProjectOptions(@Nullable ToolContext toolContext) {
        try {
            return invoiceToolMetaService.listProjects(toolContext).stream()
                    .map(option -> option(option.label(), option.value()))
                    .toList();
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> loadFlowOptions(@Nullable ToolContext toolContext) {
        try {
            return invoiceToolMetaService.listFlowNodes(toolContext).stream()
                    .map(flow -> option(flow.title(), flow.flowId()))
                    .toList();
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private List<InvoiceToolMetaService.UserRecord> loadUsers(@Nullable ToolContext toolContext) {
        try {
            return invoiceToolMetaService.listUsers(toolContext);
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private List<InvoiceToolMetaService.CustomerRecord> loadCustomers(
            Map<String, Object> values,
            @Nullable ToolContext toolContext) {
        if (!shouldSearchCustomer(values)) {
            return List.of();
        }
        try {
            return invoiceToolMetaService.searchCustomers(asText(values.get("invoice_title")), toolContext);
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private void applyDefaults(Map<String, Object> values, FieldOptions fieldOptions) {
        if (!StringUtils.hasText(asText(values.get("flow_id"))) && !fieldOptions.flowOptions().isEmpty()) {
            values.put("flow_id", asText(fieldOptions.flowOptions().get(0).get("value")));
        }
        if (!StringUtils.hasText(asText(values.get("project_name"))) && StringUtils.hasText(asText(values.get("project_id")))) {
            String label = findOptionLabelByValue(asText(values.get("project_id")), fieldOptions.projectOptions());
            if (StringUtils.hasText(label)) {
                values.put("project_name", label);
            }
        }
        if (!StringUtils.hasText(asText(values.get("check_uames"))) && StringUtils.hasText(asText(values.get("check_uids")))) {
            values.put("check_uames", resolveUserNames(values.get("check_uids"), fieldOptions.users()));
        }
        if (!StringUtils.hasText(asText(values.get("check_copy_unames")))
                && StringUtils.hasText(asText(values.get("check_copy_uids")))) {
            values.put("check_copy_unames", resolveUserNames(values.get("check_copy_uids"), fieldOptions.users()));
        }
    }

    private void normalizeValues(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        normalizeAmount(values);
        normalizeOptionField(values, "invoice_type", INVOICE_TYPE_OPTIONS);
        normalizeOptionField(values, "types", TITLE_TYPE_OPTIONS);
        normalizeSubject(values, toolContext);
        normalizeProject(values, toolContext);
        normalizeOptionField(values, "flow_id", fieldOptions.flowOptions());
        normalizeCustomer(values, fieldOptions, toolContext);
        normalizeUsers(values, "check_uids", "check_uames", fieldOptions.users(), toolContext, false);
        normalizeUsers(values, "check_copy_uids", "check_copy_unames", fieldOptions.users(), toolContext, false);
    }

    private void normalizeAmount(Map<String, Object> values) {
        BigDecimal amount = parsePositiveAmount(values.get("amount"));
        if (amount != null) {
            values.put("amount", amount.stripTrailingZeros().toPlainString());
        }
    }

    private void normalizeSubject(Map<String, Object> values, @Nullable ToolContext toolContext) {
        String rawValue = firstText(values.get("invoice_subject"));
        if (!StringUtils.hasText(rawValue)) {
            return;
        }
        invoiceToolMetaService.findInvoiceSubjectByValueOrLabel(rawValue, toolContext)
                .ifPresent(option -> values.put("invoice_subject", option.value()));
    }

    private void normalizeProject(Map<String, Object> values, @Nullable ToolContext toolContext) {
        String projectId = firstText(values.get("project_id"), values.get("project_name"));
        if (!StringUtils.hasText(projectId)) {
            return;
        }
        invoiceToolMetaService.findProjectByValueOrLabel(projectId, toolContext).ifPresent(option -> {
            values.put("project_id", option.value());
            values.put("project_name", option.label());
        });
    }

    private void normalizeCustomer(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        String types = asText(values.get("types"));
        if ("2".equals(types)) {
            clearEnterpriseFields(values);
            return;
        }

        String invoiceTitle = asText(values.get("invoice_title"));
        if (!StringUtils.hasText(invoiceTitle)) {
            return;
        }

        Optional<InvoiceToolMetaService.CustomerRecord> matchedCustomer = matchCustomer(invoiceTitle, fieldOptions.customers())
                .or(() -> invoiceToolMetaService.searchCustomers(invoiceTitle, toolContext).stream().findFirst());
        matchedCustomer.ifPresent(customer -> {
            values.put("types", "1");
            values.put("invoice_title", customer.name());
            putIfAbsent(values, "invoice_tax", customer.taxNum());
            putIfAbsent(values, "invoice_bank", customer.taxBank());
            putIfAbsent(values, "invoice_account", customer.taxBanksn());
            putIfAbsent(values, "invoice_banking", customer.taxBanking());
            putIfAbsent(values, "invoice_phone", customer.taxMobile());
            putIfAbsent(values, "invoice_address", customer.taxAddress());
        });

        if (!StringUtils.hasText(asText(values.get("types"))) && looksLikeEnterpriseTitle(invoiceTitle)) {
            values.put("types", "1");
        }
    }

    private Optional<InvoiceToolMetaService.CustomerRecord> matchCustomer(
            String rawValue,
            List<InvoiceToolMetaService.CustomerRecord> customers) {
        if (!StringUtils.hasText(rawValue) || customers == null || customers.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalizeComparisonText(rawValue);
        return customers.stream()
                .filter(customer -> normalized.equals(normalizeComparisonText(customer.name()))
                        || normalizeComparisonText(customer.name()).contains(normalized)
                        || normalized.contains(normalizeComparisonText(customer.name())))
                .findFirst();
    }

    private void normalizeUsers(
            Map<String, Object> values,
            String idField,
            String nameField,
            List<InvoiceToolMetaService.UserRecord> users,
            @Nullable ToolContext toolContext,
            boolean multiple) {
        List<InvoiceToolMetaService.UserRecord> resolvedUsers = new ArrayList<>();
        for (String candidate : toStringList(values.get(idField))) {
            findUser(users, candidate).ifPresent(resolvedUsers::add);
        }
        if (resolvedUsers.isEmpty()) {
            for (String candidate : toStringList(values.get(nameField))) {
                findUser(users, candidate)
                        .or(() -> invoiceToolMetaService.findUserByName(candidate, toolContext))
                        .ifPresent(resolvedUsers::add);
            }
        }
        if (resolvedUsers.isEmpty()) {
            return;
        }
        if (!multiple) {
            InvoiceToolMetaService.UserRecord user = resolvedUsers.get(0);
            values.put(idField, user.id());
            values.put(nameField, user.name());
            return;
        }
        values.put(idField, joinUsers(resolvedUsers, true));
        values.put(nameField, joinUsers(resolvedUsers, false));
    }

    private String joinUsers(List<InvoiceToolMetaService.UserRecord> users, boolean ids) {
        List<String> values = new ArrayList<>();
        for (InvoiceToolMetaService.UserRecord user : users) {
            String value = ids ? user.id() : user.name();
            if (StringUtils.hasText(value) && !values.contains(value)) {
                values.add(value);
            }
        }
        return String.join(",", values);
    }

    private Optional<InvoiceToolMetaService.UserRecord> findUser(
            List<InvoiceToolMetaService.UserRecord> users,
            @Nullable String rawValue) {
        if (!StringUtils.hasText(rawValue) || users == null || users.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalizeComparisonText(rawValue);
        return users.stream()
                .filter(user -> normalized.equals(normalizeComparisonText(user.id()))
                        || normalized.equals(normalizeComparisonText(user.name()))
                        || normalized.equals(normalizeComparisonText(user.displayName())))
                .findFirst();
    }

    private List<Map<String, Object>> buildFields(Map<String, Object> values, FieldOptions fieldOptions) {
        List<Map<String, Object>> fields = new ArrayList<>();
        boolean enterprise = "1".equals(asText(values.get("types")));
        boolean personal = "2".equals(asText(values.get("types")));

        fields.add(field("开票金额", "amount", "number", values.get("amount"), true, Map.of()));
        fields.add(field("发票类型", "invoice_type", "select", values.get("invoice_type"), true, Map.of(
                "options", INVOICE_TYPE_OPTIONS,
                "optionsLoaded", true)));
        fields.add(field("开票主体", "invoice_subject", "select", values.get("invoice_subject"), true, Map.of(
                "remote", SUBJECT_REMOTE,
                "labelField", "title",
                "valueField", "id",
                "options", fieldOptions.subjectOptions(),
                "optionsLoaded", !fieldOptions.subjectOptions().isEmpty())));
        fields.add(field("抬头类型", "types", "select", values.get("types"), true, Map.of(
                "options", TITLE_TYPE_OPTIONS,
                "optionsLoaded", true)));
        fields.add(buildInvoiceTitleField(values.get("invoice_title"), enterprise, fieldOptions));
        fields.add(field("电话", "invoice_phone", "input", values.get("invoice_phone"), true, Map.of()));

        if (enterprise) {
            fields.add(field("纳税识别号", "invoice_tax", "input", values.get("invoice_tax"), true, Map.of()));
            fields.add(field("开户行", "invoice_bank", "input", values.get("invoice_bank"), true, Map.of()));
            fields.add(field("银行账号", "invoice_account", "input", values.get("invoice_account"), true, Map.of()));
            fields.add(field("银行营业点", "invoice_banking", "input", values.get("invoice_banking"), false, Map.of()));
            fields.add(field("地址", "invoice_address", "textarea", values.get("invoice_address"), true, Map.of()));
        }
        else if (personal) {
            // 个人抬头不需要税号、开户地址等企业字段。
        }

        fields.add(field("关联项目", "project_id", "select", values.get("project_id"), false, Map.of(
                "remote", PROJECT_REMOTE,
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.projectOptions(),
                "optionsLoaded", !fieldOptions.projectOptions().isEmpty())));
        fields.add(field("项目名称", "project_name", "hidden", values.get("project_name"), false, Map.of(
                "editable", false,
                "displayConfig", Map.of("showInSummary", false))));
        fields.add(field("备注", "remark", "textarea", values.get("remark"), false, Map.of()));
        fields.add(field("审批流程", "flow_id", "select", values.get("flow_id"), true, Map.of(
                "remote", FLOW_REMOTE,
                "labelField", "title",
                "valueField", "flow_id",
                "options", fieldOptions.flowOptions(),
                "optionsLoaded", !fieldOptions.flowOptions().isEmpty())));
        fields.add(field("审批人", "check_uids", "select", values.get("check_uids"), true, Map.of(
                "remote", USER_REMOTE,
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions(),
                "optionsLoaded", !fieldOptions.userOptions().isEmpty())));
        fields.add(field("审批人名称", "check_uames", "hidden", values.get("check_uames"), false, Map.of(
                "editable", false,
                "displayConfig", Map.of("showInSummary", false))));
        fields.add(field("抄送人", "check_copy_uids", "select", values.get("check_copy_uids"), false, Map.of(
                "remote", USER_REMOTE,
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions(),
                "optionsLoaded", !fieldOptions.userOptions().isEmpty())));
        fields.add(field("抄送人名称", "check_copy_unames", "hidden", values.get("check_copy_unames"), false, Map.of(
                "editable", false,
                "displayConfig", Map.of("showInSummary", false))));
        return fields;
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
            if (!hasValue(values.get(name))) {
                missingFields.add(fieldRef(name));
            }
        }
        return missingFields;
    }

    private Map<String, Object> successPayload(
            InvoiceToolMetaService.AddResult result,
            InvoiceApplyRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", true);
        payload.put("toolCode", SUBMIT_TOOL_CODE);
        payload.put("artifactCode", SUBMIT_TOOL_CODE);
        payload.put("message", firstText(result.message(), "开票申请提交成功"));
        payload.put("recordId", result.recordId());
        payload.put("amount", request.amount());
        payload.put("invoice_title", request.invoiceTitle());
        payload.put("invoice_type", request.invoiceType());
        payload.put("check_uames", request.checkUames());
        return payload;
    }

    private Map<String, Object> errorPayload(
            String message,
            Map<String, Object> values,
            List<Map<String, Object>> missingFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", false);
        payload.put("toolCode", SUBMIT_TOOL_CODE);
        payload.put("artifactCode", SUBMIT_TOOL_CODE);
        payload.put("message", message);
        payload.put("error", message);
        payload.put("values", values != null ? values : Map.of());
        payload.put("missingFields", missingFields != null ? missingFields : List.of());
        return payload;
    }

    private Map<String, Object> fieldRef(String fieldName) {
        return Map.of("name", fieldName, "title", FIELD_TITLES.getOrDefault(fieldName, fieldName));
    }

    private BigDecimal parsePositiveAmount(Object rawAmount) {
        String text = asText(rawAmount);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(text);
            return amount.compareTo(BigDecimal.ZERO) > 0 ? amount : null;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> buildInvoiceTitleField(
            Object value,
            boolean enterprise,
            FieldOptions fieldOptions) {
        if (!enterprise) {
            return field("开票抬头", "invoice_title", "input", value, true, Map.of(
                    "placeholder", "请输入开票抬头"));
        }
        return field("开票抬头", "invoice_title", "select", value, true, Map.of(
                "remote", CUSTOMER_REMOTE,
                "labelField", "name",
                "valueField", "name",
                "allowCustomValue", true,
                "options", fieldOptions.customerOptions(),
                "optionsLoaded", !fieldOptions.customerOptions().isEmpty(),
                "placeholder", "可选择客户，也可手动输入开票抬头"));
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

    private String matchOptionValue(@Nullable String rawValue, List<Map<String, Object>> options) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String normalizedRawValue = normalizeComparisonText(rawValue);
        for (Map<String, Object> option : options) {
            String valueText = asText(option.get("value"));
            String labelText = firstText(option.get("label"), option.get("title"), option.get("name"));
            if (normalizedRawValue.equals(normalizeComparisonText(valueText))
                    || normalizedRawValue.equals(normalizeComparisonText(labelText))) {
                return valueText;
            }
        }
        return null;
    }

    private String findOptionLabelByValue(@Nullable String rawValue, List<Map<String, Object>> options) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String normalizedRawValue = normalizeComparisonText(rawValue);
        for (Map<String, Object> option : options) {
            String valueText = asText(option.get("value"));
            if (normalizedRawValue.equals(normalizeComparisonText(valueText))) {
                return firstText(option.get("label"), option.get("title"), option.get("name"));
            }
        }
        return null;
    }

    private String resolveUserNames(Object rawValue, List<InvoiceToolMetaService.UserRecord> users) {
        List<String> names = new ArrayList<>();
        for (String candidate : toStringList(rawValue)) {
            findUser(users, candidate).map(InvoiceToolMetaService.UserRecord::name).ifPresent(names::add);
        }
        return String.join(",", names);
    }

    private boolean shouldSearchCustomer(Map<String, Object> values) {
        String title = asText(values.get("invoice_title"));
        String types = asText(values.get("types"));
        return StringUtils.hasText(title)
                && ("1".equals(types) || !StringUtils.hasText(types) && looksLikeEnterpriseTitle(title));
    }

    private boolean looksLikeEnterpriseTitle(@Nullable String title) {
        String normalized = asText(title);
        return StringUtils.hasText(normalized)
                && containsAny(normalized, "公司", "有限", "集团", "科技", "贸易", "中心", "事务所", "学校", "医院", "银行", "厂");
    }

    private boolean containsAny(String input, String... keywords) {
        if (!StringUtils.hasText(input) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && input.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void clearIrrelevantFields(Map<String, Object> values) {
        if ("2".equals(asText(values.get("types")))) {
            clearEnterpriseFields(values);
        }
    }

    private void clearEnterpriseFields(Map<String, Object> values) {
        values.remove("invoice_tax");
        values.remove("invoice_bank");
        values.remove("invoice_account");
        values.remove("invoice_banking");
        values.remove("invoice_address");
    }

    private void putIfAbsent(Map<String, Object> values, String key, @Nullable String value) {
        if (!values.containsKey(key) && StringUtils.hasText(value)) {
            values.put(key, value);
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

    private OverAllState extractState(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return rawState instanceof OverAllState state ? state : null;
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
        String[] parts = text.split("[,，、\\s]+");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                values.add(part.trim());
            }
        }
        return values;
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

    private String normalizeComparisonText(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase(Locale.ROOT);
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

    private record ResolvedContext(Map<String, Object> values, FieldOptions fieldOptions) {
    }

    private record FieldOptions(
            List<Map<String, Object>> subjectOptions,
            List<Map<String, Object>> projectOptions,
            List<Map<String, Object>> flowOptions,
            List<InvoiceToolMetaService.UserRecord> users,
            List<Map<String, Object>> userOptions,
            List<InvoiceToolMetaService.CustomerRecord> customers,
            List<Map<String, Object>> customerOptions) {
    }
}
