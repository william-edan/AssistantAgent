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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.seal.model.SealApplyRequest;
import com.alibaba.assistant.agent.start.seal.service.SealToolMetaService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用章申请提交工具。
 *
 * <p>职责：
 * 1) 合并表单值与自然语言输入；
 * 2) 提交前将名称字段统一归一化为接口所需 ID；
 * 3) 通过 tool_meta 按“新增 + 提交审批”顺序完成流程。</p>
 */
@Component
@Profile("migration")
public class SealApplyTool extends AbstractDynamicCodeactTool {

    private static final Logger log = LoggerFactory.getLogger(SealApplyTool.class);

    public static final String TOOL_NAME = "seal_apply";

    private static final String FLOW_CHECK_NAME = "seal";

    private static final Map<String, String> FIELD_TITLES = Map.ofEntries(
            Map.entry("title", "申请主题"),
            Map.entry("did", "用章部门"),
            Map.entry("num", "盖章次数"),
            Map.entry("use_time", "预期用印日期"),
            Map.entry("seal_cate_id", "印章类型"),
            Map.entry("is_borrow", "印章是否外借"),
            Map.entry("start_time", "印章借用日期"),
            Map.entry("end_time", "结束借用日期"),
            Map.entry("content", "盖章内容"),
            Map.entry("flow_id", "审批流程"),
            Map.entry("check_uids", "审批人"),
            Map.entry("check_unames", "审批人名称"),
            Map.entry("check_copy_uids", "抄送人"),
            Map.entry("check_copy_unames", "抄送人名称"));

    private final SealToolMetaService sealToolMetaService;

    public SealApplyTool(ObjectMapper objectMapper, SealToolMetaService sealToolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.sealToolMetaService = sealToolMetaService;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        try {
            Map<String, Object> values = resolveValues(args, toolContext);
            normalizeValues(values, toolContext);

            List<Map<String, Object>> missingFields = validateRequiredFields(values);
            if (!missingFields.isEmpty()) {
                return objectMapper.writeValueAsString(errorPayload(
                        "请补全必要字段后再提交。",
                        values,
                        missingFields));
            }

            String num = asText(values.get("num"));
            if (!isPositiveInteger(num)) {
                return objectMapper.writeValueAsString(errorPayload(
                        "盖章次数必须为大于 0 的整数。",
                        values,
                        List.of(fieldRef("num"))));
            }

            UserResolution approver = resolveRequiredUser(
                    asText(values.get("check_uids")),
                    asText(values.get("check_unames")),
                    "审批人",
                    toolContext);
            if (approver.hasError()) {
                return objectMapper.writeValueAsString(errorPayload(
                        approver.errorMessage(),
                        values,
                        List.of(fieldRef("check_uids"))));
            }

            UserResolution copyUser = resolveOptionalUser(
                    asText(values.get("check_copy_uids")),
                    asText(values.get("check_copy_unames")),
                    "抄送人",
                    toolContext);
            if (copyUser.hasError()) {
                return objectMapper.writeValueAsString(errorPayload(
                        copyUser.errorMessage(),
                        values,
                        List.of(fieldRef("check_copy_uids"))));
            }

            SealApplyRequest request = new SealApplyRequest(
                    asText(values.get("title")),
                    asText(values.get("did")),
                    num,
                    asText(values.get("use_time")),
                    asText(values.get("seal_cate_id")),
                    asText(values.get("is_borrow")),
                    firstText(values.get("start_time"), values.get("use_time")),
                    firstText(values.get("end_time"), values.get("use_time")),
                    asText(values.get("content")),
                    "",
                    "",
                    asText(values.get("flow_id")),
                    approver.name(),
                    approver.id(),
                    copyUser.name(),
                    copyUser.id());

            SealToolMetaService.SubmitResult result = sealToolMetaService.createAndSubmit(request, toolContext);
            return objectMapper.writeValueAsString(successPayload(result, request));
        }
        catch (Exception exception) {
            String message = Optional.ofNullable(exception.getMessage())
                    .filter(StringUtils::hasText)
                    .orElse("用章申请提交失败");
            log.warn("SealApplyTool#doCall - failed, error={}", message, exception);
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
                mergeMap(values, pendingFormMap.get("values"));
            }
        }

        mergeMap(values, args.get("values"));
        values.putAll(SealNaturalLanguageSlotParser.parse(asText(args.get("userInput"))));
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
        if (rawValue instanceof Map<?, ?> mapValue) {
            mapValue.forEach((key, value) -> {
                if (key != null && value != null) {
                    target.put(String.valueOf(key), value);
                }
            });
        }
    }

    private void normalizeValues(Map<String, Object> values, @Nullable ToolContext toolContext) {
        values.putIfAbsent("did", "2");
        values.putIfAbsent("num", "1");
        values.put("is_borrow", normalizeBorrowFlag(firstText(values.get("is_borrow"), "0")));

        String useTime = asText(values.get("use_time"));
        if (StringUtils.hasText(useTime)) {
            values.putIfAbsent("start_time", useTime);
            values.putIfAbsent("end_time", useTime);
        }

        normalizeDepartment(values, toolContext);
        normalizeSealCategory(values, toolContext);
        normalizeFlow(values, toolContext);
        normalizeUser(values, "check_uids", "check_unames", toolContext);
        normalizeUser(values, "check_copy_uids", "check_copy_unames", toolContext);
    }

    private void normalizeDepartment(Map<String, Object> values, @Nullable ToolContext toolContext) {
        String didName = asText(values.get("did_name"));
        if (StringUtils.hasText(didName) && !StringUtils.hasText(asText(values.get("did")))) {
            sealToolMetaService.findDepartmentIdByName(didName, toolContext)
                    .ifPresent(did -> values.put("did", did));
        }

        String did = asText(values.get("did"));
        if (StringUtils.hasText(did) && !StringUtils.hasText(asText(values.get("did_name")))) {
            sealToolMetaService.findDepartmentNameById(did, toolContext)
                    .ifPresent(name -> values.put("did_name", name));
        }
    }

    private void normalizeSealCategory(Map<String, Object> values, @Nullable ToolContext toolContext) {
        String sealCategory = asText(values.get("seal_cate_id"));
        if (!StringUtils.hasText(sealCategory)) {
            return;
        }
        if (isPositiveInteger(sealCategory)) {
            return;
        }
        sealToolMetaService.findSealCategoryByTitle(sealCategory, toolContext)
                .ifPresent(record -> values.put("seal_cate_id", record.id()));
    }

    private void normalizeFlow(Map<String, Object> values, @Nullable ToolContext toolContext) {
        String flowIdOrTitle = asText(values.get("flow_id"));
        if (!StringUtils.hasText(flowIdOrTitle)) {
            return;
        }
        if (isPositiveInteger(flowIdOrTitle)) {
            return;
        }
        sealToolMetaService.findFlowNodeByTitle(FLOW_CHECK_NAME, flowIdOrTitle, toolContext)
                .ifPresent(flow -> values.put("flow_id", flow.flowId()));
    }

    private void normalizeUser(
            Map<String, Object> values,
            String userIdField,
            String userNameField,
            @Nullable ToolContext toolContext) {
        String userId = asText(values.get(userIdField));
        String userName = asText(values.get(userNameField));

        Optional<SealToolMetaService.UserRecord> user = Optional.empty();
        if (StringUtils.hasText(userId)) {
            user = sealToolMetaService.findUserById(userId, toolContext);
            if (user.isEmpty()) {
                user = sealToolMetaService.findUserByName(userId, toolContext);
            }
        }
        if (user.isEmpty() && StringUtils.hasText(userName)) {
            user = sealToolMetaService.findUserByName(userName, toolContext);
        }

        user.ifPresent(record -> {
            values.put(userIdField, record.id());
            values.put(userNameField, record.displayName());
        });
    }

    private List<Map<String, Object>> validateRequiredFields(Map<String, Object> values) {
        List<Map<String, Object>> missingFields = new ArrayList<>();
        for (String field : List.of(
                "title",
                "did",
                "num",
                "use_time",
                "seal_cate_id",
                "is_borrow",
                "start_time",
                "end_time",
                "content",
                "flow_id")) {
            if (!StringUtils.hasText(asText(values.get(field)))) {
                missingFields.add(fieldRef(field));
            }
        }
        boolean hasApproverId = StringUtils.hasText(asText(values.get("check_uids")));
        boolean hasApproverName = StringUtils.hasText(asText(values.get("check_unames")));
        if (!hasApproverId && !hasApproverName) {
            missingFields.add(fieldRef("check_uids"));
        }
        return missingFields;
    }

    private UserResolution resolveRequiredUser(
            @Nullable String userId,
            @Nullable String userName,
            String displayName,
            @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(userId) && !StringUtils.hasText(userName)) {
            return UserResolution.error("请选择" + displayName + "。");
        }
        return resolveUser(userId, userName, displayName, toolContext, true);
    }

    private UserResolution resolveOptionalUser(
            @Nullable String userId,
            @Nullable String userName,
            String displayName,
            @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(userId) && !StringUtils.hasText(userName)) {
            return UserResolution.empty();
        }
        return resolveUser(userId, userName, displayName, toolContext, false);
    }

    private UserResolution resolveUser(
            @Nullable String userId,
            @Nullable String userName,
            String displayName,
            @Nullable ToolContext toolContext,
            boolean required) {
        Optional<SealToolMetaService.UserRecord> matchedUser = Optional.empty();
        if (StringUtils.hasText(userId)) {
            matchedUser = sealToolMetaService.findUserById(userId, toolContext);
        }
        if (matchedUser.isEmpty() && StringUtils.hasText(userName)) {
            matchedUser = sealToolMetaService.findUserByName(userName, toolContext);
        }
        if (matchedUser.isPresent()) {
            SealToolMetaService.UserRecord user = matchedUser.get();
            return new UserResolution(user.id(), user.name(), null);
        }
        if (!required && !StringUtils.hasText(userId) && !StringUtils.hasText(userName)) {
            return UserResolution.empty();
        }
        return UserResolution.error("未找到对应" + displayName + "，请重新选择。");
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

    private boolean isPositiveInteger(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            return Integer.parseInt(value) > 0;
        }
        catch (NumberFormatException exception) {
            return false;
        }
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

    private Map<String, Object> successPayload(SealToolMetaService.SubmitResult result, SealApplyRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", true);
        payload.put("toolCode", TOOL_NAME);
        payload.put("artifactCode", TOOL_NAME);
        payload.put("message", firstText(result.message(), "用章申请提交成功"));
        payload.put("recordId", result.recordId());
        payload.put("title", request.title());
        payload.put("use_time", request.useTime());
        payload.put("check_unames", request.checkUnames());
        payload.put("did", request.did());
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
        if (values != null && !values.isEmpty()) {
            payload.put("values", values);
        }
        if (missingFields != null && !missingFields.isEmpty()) {
            payload.put("missingFields", missingFields);
        }
        return payload;
    }

    private Map<String, Object> fieldRef(String fieldName) {
        return Map.of(
                "name", fieldName,
                "title", FIELD_TITLES.getOrDefault(fieldName, fieldName));
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
                .description("Submit a seal application and trigger approval via tool_meta.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "title": {"type": "string"},
                            "did": {"type": "string"},
                            "did_name": {"type": "string"},
                            "num": {"type": "string"},
                            "use_time": {"type": "string"},
                            "seal_cate_id": {"type": "string"},
                            "is_borrow": {"type": "string"},
                            "start_time": {"type": "string"},
                            "end_time": {"type": "string"},
                            "content": {"type": "string"},
                            "flow_id": {"type": "string"},
                            "check_uids": {"type": "string"},
                            "check_unames": {"type": "string"},
                            "check_copy_uids": {"type": "string"},
                            "check_copy_unames": {"type": "string"},
                            "userInput": {"type": "string"},
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
                .targetClassName("seal_apply_tools")
                .targetClassDescription("Seal apply submit tools")
                .fewShots(List.of(new CodeExample(
                        "submit seal apply",
                        "result = seal_apply(title='合同盖章', did='1', num='1', use_time='2026-04-16', seal_cate_id='1', is_borrow='0', content='用于合同签章', flow_id='5', check_uids='2')",
                        "提交用章申请并发起审批")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }

    private record UserResolution(String id, String name, String errorMessage) {

        private static UserResolution empty() {
            return new UserResolution("", "", null);
        }

        private static UserResolution error(String errorMessage) {
            return new UserResolution("", "", errorMessage);
        }

        private boolean hasError() {
            return StringUtils.hasText(errorMessage);
        }
    }
}
