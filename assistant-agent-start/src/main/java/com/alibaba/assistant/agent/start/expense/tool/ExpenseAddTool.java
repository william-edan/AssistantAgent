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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.expense.model.ExpenseAddDetail;
import com.alibaba.assistant.agent.start.expense.model.ExpenseAddRequest;
import com.alibaba.assistant.agent.start.expense.service.ExpenseToolMetaService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 报销申请提交工具。
 *
 * <p>职责：
 * 1) 合并前端 pendingForm 与当前轮输入；
 * 2) 规范化报销人、审批人和多条报销明细；
 * 3) 通过 tool_meta 提交真实报销接口。</p>
 */
@Component
@Profile("migration")
public class ExpenseAddTool extends AbstractDynamicCodeactTool {

    private static final Logger log = LoggerFactory.getLogger(ExpenseAddTool.class);

    public static final String TOOL_NAME = "expense_add";

    private static final Map<String, String> FIELD_TITLES = Map.ofEntries(
            Map.entry("subject_id", "报销主体"),
            Map.entry("code", "凭证编号"),
            Map.entry("expense_time", "报销日期"),
            Map.entry("income_month", "所属月份"),
            Map.entry("project_id", "关联项目"),
            Map.entry("ptname", "报销人"),
            Map.entry("department", "所属部门"),
            Map.entry("details", "报销明细"),
            Map.entry("check_uids", "审批人"),
            Map.entry("check_copy_uids", "抄送人"));

    private final ExpenseToolMetaService expenseToolMetaService;

    public ExpenseAddTool(ObjectMapper objectMapper, ExpenseToolMetaService expenseToolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.expenseToolMetaService = expenseToolMetaService;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        try {
            Map<String, Object> values = resolveValues(args, toolContext);
            List<Map<String, Object>> missingFields = validateRequiredFields(values);
            if (!missingFields.isEmpty()) {
                return objectMapper.writeValueAsString(errorPayload(
                        "请补全必要字段后再提交。",
                        values,
                        missingFields));
            }

            ExpenseToolMetaService.UserRecord applicant = resolveUser(
                    values.get("ptname"),
                    "报销人",
                    toolContext);
            ResolvedUsers approver = resolveRequiredUsers(values.get("check_uids"), "审批人", toolContext);
            ResolvedUsers copyUsers = resolveOptionalUsers(values.get("check_copy_uids"), "抄送人", toolContext);
            List<ExpenseAddDetail> details = resolveDetails(values.get("details"), toolContext);

            ExpenseAddRequest request = new ExpenseAddRequest(
                    asText(values.get("subject_id")),
                    asText(values.get("code")),
                    asText(values.get("expense_time")),
                    asText(values.get("income_month")),
                    asText(values.get("project_id")),
                    asText(values.get("flow_id")),
                    applicant.id(),
                    applicant.name(),
                    firstText(values.get("department"), applicant.departmentName()),
                    approver.names(),
                    approver.ids(),
                    copyUsers.names(),
                    copyUsers.ids(),
                    details);

            ExpenseToolMetaService.AddResult result = expenseToolMetaService.createAndSubmitExpense(request, toolContext);
            return objectMapper.writeValueAsString(successPayload(result, request));
        }
        catch (Exception exception) {
            String message = Optional.ofNullable(exception.getMessage())
                    .filter(StringUtils::hasText)
                    .orElse("报销申请提交失败");
            if (isDuplicateCodeMessage(message)) {
                message = "凭证编号重复，请修改后重新提交";
            }
            log.warn("ExpenseAddTool#doCall - failed, error={}", message, exception);
            return objectMapper.writeValueAsString(errorPayload(message, Map.of(), List.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveValues(Map<String, Object> args, @Nullable ToolContext toolContext) {
        Map<String, Object> values = new LinkedHashMap<>();

        Object frontendThreadState = args.get("frontendThreadState");
        if (frontendThreadState instanceof Map<?, ?> stateMap) {
            Object pendingForm = stateMap.get("pendingForm");
            if (pendingForm instanceof Map<?, ?> pendingFormMap
                    && TOOL_NAME.equals(asText(pendingFormMap.get("toolCode")))) {
                Object pendingValues = pendingFormMap.get("values");
                if (pendingValues instanceof Map<?, ?> valueMap) {
                    valueMap.forEach((key, value) -> {
                        if (key != null && value != null) {
                            values.put(String.valueOf(key), value);
                        }
                    });
                }
            }
        }

        Object rawValues = args.get("values");
        if (rawValues instanceof Map<?, ?> valueMap) {
            valueMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }

        Object rawSlotInputs = args.get("slotInputs");
        if (rawSlotInputs instanceof Map<?, ?> slotInputs) {
            slotInputs.forEach((key, value) -> {
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

        OverAllState state = extractState(toolContext);
        if (state != null) {
            Object currentTurnInputs = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
            if (currentTurnInputs instanceof Map<?, ?> inputMap) {
                inputMap.forEach((key, value) -> {
                    if (key != null && value != null) {
                        values.put(String.valueOf(key), value);
                    }
                });
            }
        }
        return values;
    }

    private List<Map<String, Object>> validateRequiredFields(Map<String, Object> values) {
        List<Map<String, Object>> missingFields = new ArrayList<>();
        for (String fieldName : List.of(
                "subject_id",
                "code",
                "expense_time",
                "income_month",
                "project_id",
                "flow_id",
                "ptname",
                "check_uids")) {
            if (!StringUtils.hasText(asText(values.get(fieldName)))) {
                missingFields.add(fieldRef(fieldName));
            }
        }
        if (!hasCompleteDetails(values.get("details"))) {
            missingFields.add(fieldRef("details"));
        }
        return missingFields;
    }

    private ExpenseToolMetaService.UserRecord resolveUser(
            Object rawValue,
            String displayName,
            @Nullable ToolContext toolContext) {
        for (String candidate : toStringList(rawValue)) {
            Optional<ExpenseToolMetaService.UserRecord> user = expenseToolMetaService.findUserById(candidate, toolContext)
                    .or(() -> expenseToolMetaService.findUserByName(candidate, toolContext));
            if (user.isPresent()) {
                return user.get();
            }
        }
        throw new IllegalStateException("未找到对应" + displayName + "，请重新选择。");
    }

    private ResolvedUsers resolveRequiredUsers(
            Object rawValue,
            String displayName,
            @Nullable ToolContext toolContext) {
        ResolvedUsers resolvedUsers = resolveOptionalUsers(rawValue, displayName, toolContext);
        if (!StringUtils.hasText(resolvedUsers.ids())) {
            throw new IllegalStateException("请选择" + displayName + "。");
        }
        return resolvedUsers;
    }

    private ResolvedUsers resolveOptionalUsers(
            Object rawValue,
            String displayName,
            @Nullable ToolContext toolContext) {
        List<String> userIds = new ArrayList<>();
        List<String> userNames = new ArrayList<>();
        for (String candidate : toStringList(rawValue)) {
            Optional<ExpenseToolMetaService.UserRecord> user = expenseToolMetaService.findUserById(candidate, toolContext)
                    .or(() -> expenseToolMetaService.findUserByName(candidate, toolContext));
            if (user.isEmpty()) {
                throw new IllegalStateException("未找到对应" + displayName + "，请重新选择。");
            }
            userIds.add(user.get().id());
            userNames.add(user.get().name());
        }
        return new ResolvedUsers(String.join(",", userIds), String.join(",", userNames));
    }

    private List<ExpenseAddDetail> resolveDetails(Object rawDetails, @Nullable ToolContext toolContext) {
        List<ExpenseAddDetail> details = new ArrayList<>();
        for (Map<String, Object> rawDetail : toListOfMaps(rawDetails)) {
            String cateId = firstText(rawDetail.get("cate_id"), rawDetail.get("cate_name"));
            if (!StringUtils.hasText(cateId)) {
                continue;
            }
            cateId = expenseToolMetaService.findExpenseCategoryByValueOrLabel(cateId, toolContext)
                    .map(ExpenseToolMetaService.OptionRecord::value)
                    .orElse(cateId);

            BigDecimal amount = parseAmount(rawDetail.get("amount"));
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("报销金额必须大于 0。");
            }

            details.add(new ExpenseAddDetail(
                    cateId,
                    amount,
                    asText(rawDetail.get("remarks")),
                    firstText(rawDetail.get("expense_id"), "0")));
        }
        if (details.isEmpty()) {
            throw new IllegalStateException("请至少填写一条完整的报销明细。");
        }
        return details;
    }

    private boolean hasCompleteDetails(Object rawDetails) {
        List<Map<String, Object>> details = toListOfMaps(rawDetails);
        if (details.isEmpty()) {
            return false;
        }
        return details.stream().allMatch(detail ->
                StringUtils.hasText(firstText(detail.get("cate_id"), detail.get("cate_name")))
                        && parseAmount(detail.get("amount")) != null);
    }

    private BigDecimal parseAmount(Object rawAmount) {
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

    private boolean isBusinessField(String key) {
        return StringUtils.hasText(key)
                && !"userInput".equals(key)
                && !"confirmed".equals(key)
                && !"slotInputs".equals(key)
                && !"values".equals(key)
                && !"frontendThreadState".equals(key);
    }

    private OverAllState extractState(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return rawState instanceof OverAllState state ? state : null;
    }

    private Map<String, Object> successPayload(ExpenseToolMetaService.AddResult result, ExpenseAddRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", true);
        payload.put("toolCode", TOOL_NAME);
        payload.put("artifactCode", TOOL_NAME);
        payload.put("message", firstText(result.message(), "报销申请提交成功"));
        payload.put("recordId", result.recordId());
        payload.put("code", request.code());
        payload.put("ptname", request.applicantName());
        payload.put("detailCount", request.details().size());
        payload.put("totalAmount", request.details().stream()
                .map(ExpenseAddDetail::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .stripTrailingZeros()
                .toPlainString());
        return payload;
    }

    private Map<String, Object> errorPayload(
            String message,
            Map<String, Object> values,
            List<Map<String, Object>> missingFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", false);
        payload.put("toolCode", TOOL_NAME);
        payload.put("artifactCode", TOOL_NAME);
        payload.put("message", message);
        payload.put("error", message);
        payload.put("values", values != null ? values : Map.of());
        payload.put("missingFields", missingFields != null ? missingFields : List.of());
        return payload;
    }

    private Map<String, Object> fieldRef(String fieldName) {
        return Map.of(
                "name", fieldName,
                "title", "flow_id".equals(fieldName)
                        ? "审批流程"
                        : FIELD_TITLES.getOrDefault(fieldName, fieldName));
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
        String[] parts = text.split("[,，、\\s]+");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private boolean isDuplicateCodeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return (message.contains("凭证") || message.toLowerCase().contains("code"))
                && (message.contains("重复") || message.contains("已存在") || message.toLowerCase().contains("duplicate"));
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

    private record ResolvedUsers(String ids, String names) {
    }

    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Submit an expense application through tool_meta.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "subject_id": {"type": "string"},
                            "code": {"type": "string"},
                            "expense_time": {"type": "string"},
                            "income_month": {"type": "string"},
                            "project_id": {"type": "string"},
                            "flow_id": {"type": "string"},
                            "ptname": {"type": "string"},
                            "department": {"type": "string"},
                            "details": {"type": "array"},
                            "check_uids": {"type": "string"},
                            "check_copy_uids": {"type": "string"},
                            "confirmed": {"type": "boolean"},
                            "values": {"type": "object"},
                            "slotInputs": {"type": "object"},
                            "frontendThreadState": {"type": "object"}
                          }
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("expense_add_tools")
                .targetClassDescription("Expense add submit tools")
                .fewShots(List.of(new CodeExample(
                        "submit expense add",
                        "result = expense_add(subject_id='1', code='BX20260410', expense_time='2026-04-10', income_month='2026-04', project_id='1000', ptname='2', check_uids='6', details=[{'cate_id':'11','amount':'100','remarks':'打车'}])",
                        "提交报销申请")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}
