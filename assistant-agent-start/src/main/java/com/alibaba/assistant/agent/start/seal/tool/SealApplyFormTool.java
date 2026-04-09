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
package com.alibaba.assistant.agent.start.seal.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.seal.service.SealToolMetaService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 用章申请表单工具。
 *
 * <p>职责：
 * 1) 拉取并预填印章类型/审批流程/审批人下拉；
 * 2) 将自然语言中的“名称值”归一化成提交所需 ID；
 * 3) 返回与请假流程一致的 FORM 结构。</p>
 */
@Component
@Profile("migration")
public class SealApplyFormTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "seal_apply_form";

    private static final Logger log = LoggerFactory.getLogger(SealApplyFormTool.class);

    private static final String FLOW_CHECK_NAME = "seal";

    private static final String FLOW_REMOTE = "/api/check/get_flow_nodes?check_name=seal&action_id=0&flow_id=0";

    private static final List<Map<String, Object>> BORROW_OPTIONS = List.of(
            Map.of("label", "否", "value", "0"),
            Map.of("label", "是", "value", "1"));

    private final SealToolMetaService sealToolMetaService;

    public SealApplyFormTool(ObjectMapper objectMapper, SealToolMetaService sealToolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.sealToolMetaService = sealToolMetaService;
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
        String message = canSubmit ? "请确认用章申请信息后提交。" : "请补全用章申请信息。";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "FORM");
        payload.put("type", "form");
        payload.put("title", "新增用章申请");
        payload.put("toolCode", SealApplyTool.TOOL_NAME);
        payload.put("artifactCode", SealApplyTool.TOOL_NAME);
        payload.put("submit_tool", SealApplyTool.TOOL_NAME);
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

        Map<String, Object> parsedValues = SealNaturalLanguageSlotParser.parse(asText(args.get("userInput")));
        values.putAll(parsedValues);

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

        applyDefaults(values, fieldOptions, toolContext);
        normalizeValues(values, fieldOptions, toolContext);
        applyDefaults(values, fieldOptions, toolContext);
        return values;
    }

    private void applyDefaults(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        values.putIfAbsent("did", "2");
        values.putIfAbsent("num", "1");
        values.put("is_borrow", normalizeBorrowFlag(firstText(values.get("is_borrow"), "0")));

        if (!StringUtils.hasText(asText(values.get("seal_cate_id")))
                && fieldOptions.sealCategoryOptions().options().size() == 1) {
            values.put("seal_cate_id", asText(fieldOptions.sealCategoryOptions().options().get(0).get("value")));
        }
        if (!StringUtils.hasText(asText(values.get("flow_id")))
                && fieldOptions.flowOptions().options().size() == 1) {
            values.put("flow_id", asText(fieldOptions.flowOptions().options().get(0).get("value")));
        }

        String useTime = asText(values.get("use_time"));
        if (StringUtils.hasText(useTime)) {
            values.putIfAbsent("start_time", useTime);
            values.putIfAbsent("end_time", useTime);
        }

        String did = asText(values.get("did"));
        if (!StringUtils.hasText(asText(values.get("did_name"))) && StringUtils.hasText(did)) {
            String label = findOptionLabelByValue(did, fieldOptions.departmentOptions().options());
            if (StringUtils.hasText(label)) {
                values.put("did_name", label);
            }
            else {
                sealToolMetaService.findDepartmentNameById(did, toolContext)
                        .ifPresent(name -> values.put("did_name", name));
            }
        }

        if (!StringUtils.hasText(asText(values.get("check_unames")))
                && StringUtils.hasText(asText(values.get("check_uids")))) {
            String label = findOptionLabelByValue(asText(values.get("check_uids")), fieldOptions.userOptions().options());
            if (StringUtils.hasText(label)) {
                values.put("check_unames", label);
            }
        }
        if (!StringUtils.hasText(asText(values.get("check_copy_unames")))
                && StringUtils.hasText(asText(values.get("check_copy_uids")))) {
            String label = findOptionLabelByValue(asText(values.get("check_copy_uids")), fieldOptions.userOptions().options());
            if (StringUtils.hasText(label)) {
                values.put("check_copy_unames", label);
            }
        }
    }

    private void normalizeValues(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        values.put("is_borrow", normalizeBorrowFlag(asText(values.get("is_borrow"))));
        normalizeDepartment(values, fieldOptions, toolContext);
        normalizeSealCategory(values, fieldOptions, toolContext);
        normalizeFlow(values, fieldOptions, toolContext);
        normalizeUser(values, "check_uids", "check_unames", fieldOptions, toolContext);
        normalizeUser(values, "check_copy_uids", "check_copy_unames", fieldOptions, toolContext);
    }

    private void normalizeDepartment(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        String didName = asText(values.get("did_name"));
        if (StringUtils.hasText(didName)) {
            String matchedDid = matchOptionValue(didName, fieldOptions.departmentOptions().options());
            if (StringUtils.hasText(matchedDid)) {
                values.put("did", matchedDid);
            }
            else {
                sealToolMetaService.findDepartmentIdByName(didName, toolContext)
                        .ifPresent(did -> values.put("did", did));
            }
        }

        String did = asText(values.get("did"));
        if (StringUtils.hasText(did)) {
            String label = findOptionLabelByValue(did, fieldOptions.departmentOptions().options());
            if (StringUtils.hasText(label)) {
                values.put("did_name", label);
            }
            else {
                sealToolMetaService.findDepartmentNameById(did, toolContext)
                        .ifPresent(name -> values.put("did_name", name));
            }
        }
    }

    private void normalizeSealCategory(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        String rawValue = asText(values.get("seal_cate_id"));
        if (!StringUtils.hasText(rawValue)) {
            return;
        }

        String matchedId = matchOptionValue(rawValue, fieldOptions.sealCategoryOptions().options());
        if (StringUtils.hasText(matchedId)) {
            values.put("seal_cate_id", matchedId);
            return;
        }

        sealToolMetaService.findSealCategoryByTitle(rawValue, toolContext)
                .ifPresent(record -> values.put("seal_cate_id", record.id()));
    }

    private void normalizeFlow(
            Map<String, Object> values,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        String rawValue = asText(values.get("flow_id"));
        if (!StringUtils.hasText(rawValue)) {
            return;
        }

        String matchedId = matchOptionValue(rawValue, fieldOptions.flowOptions().options());
        if (StringUtils.hasText(matchedId)) {
            values.put("flow_id", matchedId);
            return;
        }

        sealToolMetaService.findFlowNodeByTitle(FLOW_CHECK_NAME, rawValue, toolContext)
                .ifPresent(record -> values.put("flow_id", record.flowId()));
    }

    private void normalizeUser(
            Map<String, Object> values,
            String userIdField,
            String userNameField,
            FieldOptions fieldOptions,
            @Nullable ToolContext toolContext) {
        String userId = asText(values.get(userIdField));
        String userName = asText(values.get(userNameField));

        if (StringUtils.hasText(userId)) {
            String matchedId = matchOptionValue(userId, fieldOptions.userOptions().options());
            if (StringUtils.hasText(matchedId)) {
                userId = matchedId;
                values.put(userIdField, matchedId);
            }
        }

        Optional<SealToolMetaService.UserRecord> userRecord = Optional.empty();
        if (StringUtils.hasText(userId)) {
            userRecord = sealToolMetaService.findUserById(userId, toolContext);
        }
        if (userRecord.isEmpty() && StringUtils.hasText(userName)) {
            String matchedId = matchOptionValue(userName, fieldOptions.userOptions().options());
            if (StringUtils.hasText(matchedId)) {
                userId = matchedId;
                values.put(userIdField, matchedId);
                userRecord = sealToolMetaService.findUserById(matchedId, toolContext);
            }
        }
        if (userRecord.isEmpty() && StringUtils.hasText(userName)) {
            userRecord = sealToolMetaService.findUserByName(userName, toolContext);
            userRecord.ifPresent(record -> values.put(userIdField, record.id()));
        }

        userRecord.ifPresent(record -> {
            values.put(userIdField, record.id());
            values.put(userNameField, record.displayName());
        });
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
            if (!StringUtils.hasText(asText(values.get(name)))) {
                missingFields.add(Map.of(
                        "name", name,
                        "title", firstText(field.get("title"), field.get("label"), name)));
            }
        }
        return missingFields;
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
        OptionGroup sealCategoryOptions = loadSealCategoryOptions(toolContext);
        UserOptionGroup userOptionGroup = loadUserOptions(toolContext);
        OptionGroup flowOptions = loadFlowOptions(toolContext);
        OptionGroup departmentOptions = loadDepartmentOptions(toolContext, userOptionGroup);
        return new FieldOptions(sealCategoryOptions, userOptionGroup.options(), flowOptions, departmentOptions);
    }

    private OptionGroup loadSealCategoryOptions(@Nullable ToolContext toolContext) {
        try {
            List<Map<String, Object>> options = sealToolMetaService.listSealCategories(toolContext).stream()
                    .map(category -> option(category.title(), category.id()))
                    .toList();
            return new OptionGroup(options, true);
        }
        catch (Exception exception) {
            log.warn(
                    "SealApplyFormTool#loadSealCategoryOptions - preload failed, fallback to frontend remote, error={}",
                    exception.getMessage());
            return new OptionGroup(List.of(), false);
        }
    }

    private UserOptionGroup loadUserOptions(@Nullable ToolContext toolContext) {
        try {
            List<SealToolMetaService.UserRecord> users = sealToolMetaService.listUsers(toolContext);
            List<Map<String, Object>> options = users.stream()
                    .map(user -> option(user.displayName(), user.id()))
                    .toList();
            return new UserOptionGroup(users, new OptionGroup(options, true));
        }
        catch (Exception exception) {
            log.warn(
                    "SealApplyFormTool#loadUserOptions - preload failed, fallback to frontend remote, error={}",
                    exception.getMessage());
            return new UserOptionGroup(List.of(), new OptionGroup(List.of(), false));
        }
    }

    private OptionGroup loadFlowOptions(@Nullable ToolContext toolContext) {
        try {
            List<Map<String, Object>> options = sealToolMetaService
                    .listFlowNodes(FLOW_CHECK_NAME, "0", "0", toolContext)
                    .stream()
                    .map(flow -> option(flow.title(), flow.flowId()))
                    .toList();
            return new OptionGroup(options, true);
        }
        catch (Exception exception) {
            log.warn(
                    "SealApplyFormTool#loadFlowOptions - preload failed, fallback to frontend remote, error={}",
                    exception.getMessage());
            return new OptionGroup(List.of(), false);
        }
    }

    private OptionGroup loadDepartmentOptions(
            @Nullable ToolContext toolContext,
            UserOptionGroup userOptionGroup) {
        try {
            List<Map<String, Object>> options = sealToolMetaService.listDepartments(toolContext).stream()
                    .map(department -> option(department.name(), department.id()))
                    .toList();
            if (!options.isEmpty()) {
                return new OptionGroup(options, true);
            }
        }
        catch (Exception exception) {
            log.warn(
                    "SealApplyFormTool#loadDepartmentOptions - preload failed, fallback to user-derived departments, error={}",
                    exception.getMessage());
        }
        return buildDepartmentOptions(userOptionGroup.users(), userOptionGroup.options().loaded());
    }

    private OptionGroup buildDepartmentOptions(
            List<SealToolMetaService.UserRecord> users,
            boolean usersLoaded) {
        Map<String, String> deduplicated = new LinkedHashMap<>();
        for (SealToolMetaService.UserRecord user : users) {
            if (StringUtils.hasText(user.did()) && StringUtils.hasText(user.departmentName())) {
                deduplicated.putIfAbsent(user.did(), user.departmentName());
            }
        }
        List<Map<String, Object>> options = deduplicated.entrySet().stream()
                .map(entry -> option(entry.getValue(), entry.getKey()))
                .toList();
        return new OptionGroup(options, usersLoaded);
    }

    private List<Map<String, Object>> buildFields(Map<String, Object> values, FieldOptions fieldOptions) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("申请主题", "title", "input", values.get("title"), true, Map.of()));
        fields.add(field("盖章次数", "num", "number", values.get("num"), true, Map.of()));
        fields.add(field("预期用印日期", "use_time", "date", values.get("use_time"), true, Map.of()));
        fields.add(field("印章类型", "seal_cate_id", "select", values.get("seal_cate_id"), true, Map.of(
                "remote", "/adm/sealcate/datalist",
                "labelField", "title",
                "valueField", "id",
                "options", fieldOptions.sealCategoryOptions().options(),
                "optionsLoaded", fieldOptions.sealCategoryOptions().loaded())));
        fields.add(field("印章是否外借", "is_borrow", "select", values.get("is_borrow"), true, Map.of(
                "options", BORROW_OPTIONS,
                "optionsLoaded", true)));
        fields.add(field("印章借用日期", "start_time", "date", values.get("start_time"), true, Map.of()));
        fields.add(field("结束借用日期", "end_time", "date", values.get("end_time"), true, Map.of()));
        fields.add(field("盖章内容", "content", "textarea", values.get("content"), true, Map.of()));
        fields.add(field("审批流程", "flow_id", "select", values.get("flow_id"), true, Map.of(
                "remote", FLOW_REMOTE,
                "labelField", "title",
                "valueField", "flow_id",
                "options", fieldOptions.flowOptions().options(),
                "optionsLoaded", fieldOptions.flowOptions().loaded())));
        fields.add(field("审批人", "check_uids", "select", values.get("check_uids"), true, Map.of(
                "remote", "/api/oa_integration/get_all_users",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions().options(),
                "optionsLoaded", fieldOptions.userOptions().loaded())));
        fields.add(field("抄送人", "check_copy_uids", "select", values.get("check_copy_uids"), false, Map.of(
                "remote", "/api/oa_integration/get_all_users",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions().options(),
                "optionsLoaded", fieldOptions.userOptions().loaded())));
        return fields;
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

    private String normalizeBorrowFlag(@Nullable String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return "0";
        }
        String value = rawValue.trim();
        if (value.contains("否") || value.contains("不")) {
            return "0";
        }
        if (value.contains("是")) {
            return "1";
        }
        if ("0".equals(value) || "1".equals(value)) {
            return value;
        }
        return "0";
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

    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Return the seal-application form schema for frontend rendering.")
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
                .targetClassName("seal_apply_tools")
                .targetClassDescription("Seal apply form tools")
                .fewShots(List.of(new CodeExample(
                        "open seal apply form",
                        "result = seal_apply_form(userInput='我要用章')",
                        "返回用章申请表单")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }

    private record OptionGroup(List<Map<String, Object>> options, boolean loaded) {
    }

    private record UserOptionGroup(
            List<SealToolMetaService.UserRecord> users,
            OptionGroup options) {
    }

    private record FieldOptions(
            OptionGroup sealCategoryOptions,
            OptionGroup userOptions,
            OptionGroup flowOptions,
            OptionGroup departmentOptions) {
    }
}
