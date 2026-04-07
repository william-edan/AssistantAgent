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
package com.alibaba.assistant.agent.start.reward.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.reward.model.RewardIntentResult;
import com.alibaba.assistant.agent.start.reward.model.RewardNodeResult;
import com.alibaba.assistant.agent.start.reward.model.RewardWorkflowContext;
import com.alibaba.assistant.agent.start.reward.model.RewardWorkflowMode;
import com.alibaba.assistant.agent.start.reward.node.RewardAutoExecuteNode;
import com.alibaba.assistant.agent.start.reward.node.RewardFormNode;
import com.alibaba.assistant.agent.start.reward.node.RewardIntentNode;
import com.alibaba.assistant.agent.start.reward.util.RewardErrorMessageUtil;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 员工奖惩流程工具。
 */
@Component
@Profile("migration")
public class RewardWorkflowTool extends AbstractDynamicCodeactTool {

    private static final Logger log = LoggerFactory.getLogger(RewardWorkflowTool.class);

    public static final String TOOL_NAME = "reward_workflow";

    private final RewardIntentNode rewardIntentNode;

    private final RewardFormNode rewardFormNode;

    @SuppressWarnings("unused")
    private final RewardAutoExecuteNode rewardAutoExecuteNode;

    public RewardWorkflowTool(
            ObjectMapper objectMapper,
            RewardIntentNode rewardIntentNode,
            RewardFormNode rewardFormNode,
            RewardAutoExecuteNode rewardAutoExecuteNode) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.rewardIntentNode = rewardIntentNode;
        this.rewardFormNode = rewardFormNode;
        this.rewardAutoExecuteNode = rewardAutoExecuteNode;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        try {
            String userInput = resolveUserInput(args, toolContext);
            PendingRewardForm pendingRewardForm = resolvePendingRewardForm(toolContext);
            boolean startFreshRequest = shouldStartFreshRequest(userInput, pendingRewardForm);
            PendingRewardForm effectivePendingForm = startFreshRequest ? PendingRewardForm.inactive() : pendingRewardForm;
            if (!effectivePendingForm.active() && !isRewardRequest(userInput)) {
                return objectMapper.writeValueAsString(errorPayload("当前输入不属于员工奖惩流程"));
            }

            Map<String, Object> slotInputs = resolveSlotInputs(args, toolContext, effectivePendingForm);
            RewardIntentResult intentResult = mergeIntentResult(
                    rewardIntentNode.identify(userInput),
                    effectivePendingForm,
                    slotInputs);
            boolean confirmed = startFreshRequest ? false : resolveConfirmed(args, userInput, effectivePendingForm);
            RewardWorkflowContext context = new RewardWorkflowContext(
                    userInput,
                    slotInputs,
                    confirmed,
                    intentResult,
                    toolContext);

            log.info("RewardWorkflowTool#doCall - mode={}, confirmed={}", intentResult.mode(), confirmed);
            RewardNodeResult nodeResult = rewardFormNode.handle(context)
                    .blockOptional()
                    .orElseGet(() -> new RewardNodeResult("ERROR", true, errorPayload("奖惩流程未返回结果")));
            Map<String, Object> normalizedPayload = normalizeNodeResult(nodeResult);
            log.info(
                    "RewardWorkflowTool#doCall - phase={}, success={}, error={}, message={}",
                    normalizedPayload.get("phase"),
                    normalizedPayload.get("success"),
                    normalizedPayload.get("error"),
                    normalizedPayload.get("message"));
            return objectMapper.writeValueAsString(normalizedPayload);
        }
        catch (Exception exception) {
            String message = RewardErrorMessageUtil.resolveMessage(exception, "奖惩流程执行失败");
            log.warn("RewardWorkflowTool#doCall - failed, error={}", message, exception);
            return objectMapper.writeValueAsString(errorPayload(message));
        }
    }

    private String resolveUserInput(Map<String, Object> args, @Nullable ToolContext toolContext) {
        String userInput = Optional.ofNullable(args.get("userInput"))
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .orElseGet(() -> Optional.ofNullable(args.get("query"))
                        .map(String::valueOf)
                        .filter(StringUtils::hasText)
                        .orElse(null));
        if (StringUtils.hasText(userInput)) {
            return userInput;
        }
        return Optional.ofNullable(extractState(toolContext))
                .map(state -> state.value(AssistantStateKeys.CURRENT_TURN_USER_INPUT, String.class).orElse(null))
                .filter(StringUtils::hasText)
                .orElse("");
    }

    private Map<String, Object> resolveSlotInputs(
            Map<String, Object> args,
            @Nullable ToolContext toolContext,
            PendingRewardForm pendingRewardForm) {
        Map<String, Object> slotInputs = new LinkedHashMap<>(pendingRewardForm.values());
        slotInputs.putAll(extractArgumentSlotInputs(args));
        OverAllState state = extractState(toolContext);
        if (state == null) {
            return slotInputs;
        }
        Object raw = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
        if (raw instanceof Map<?, ?> currentTurnSlotInputs) {
            currentTurnSlotInputs.forEach((key, value) -> {
                if (key != null && value != null) {
                    slotInputs.put(String.valueOf(key), value);
                }
            });
        }
        return slotInputs;
    }

    private Map<String, Object> extractArgumentSlotInputs(Map<String, Object> args) {
        Map<String, Object> slotInputs = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if (!isReservedArg(key) && value != null) {
                slotInputs.put(key, value);
            }
        });
        Object rawSlotInputs = args.get("slotInputs");
        if (rawSlotInputs instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> {
                if (key != null && value != null) {
                    slotInputs.put(String.valueOf(key), value);
                }
            });
        }
        return slotInputs;
    }

    private boolean isReservedArg(String key) {
        return !StringUtils.hasText(key)
                || "userInput".equals(key)
                || "query".equals(key)
                || "confirmed".equals(key)
                || "slotInputs".equals(key);
    }

    private RewardIntentResult mergeIntentResult(
            RewardIntentResult identified,
            PendingRewardForm pendingRewardForm,
            Map<String, Object> slotInputs) {
        RewardWorkflowMode mode = pendingRewardForm.active() ? RewardWorkflowMode.FORM : identified.mode();
        Integer types = firstInteger(slotInputs.get("types"), pendingRewardForm.values().get("types"), identified.types());
        String uname = firstText(slotInputs.get("uname"), pendingRewardForm.values().get("uname"), identified.uname());
        BigDecimal amount = firstDecimal(slotInputs.get("cost"), pendingRewardForm.values().get("cost"), identified.amount());
        LocalDate rewardDate = firstDate(
                slotInputs.get("rewards_time"),
                pendingRewardForm.values().get("rewards_time"),
                identified.rewardDate());
        String remark = firstText(slotInputs.get("remark"), pendingRewardForm.values().get("remark"), identified.remark());
        String sceneKeyword = firstText(
                slotInputs.get("sceneKeyword"),
                pendingRewardForm.values().get("sceneKeyword"),
                identified.sceneKeyword());
        return new RewardIntentResult(mode, types, uname, amount, rewardDate, remark, sceneKeyword);
    }

    private boolean resolveConfirmed(Map<String, Object> args, String userInput, PendingRewardForm pendingRewardForm) {
        if (Boolean.TRUE.equals(args.get("confirmed"))) {
            return true;
        }
        return pendingRewardForm.confirming() && isConfirmText(userInput);
    }

    private Map<String, Object> normalizeNodeResult(RewardNodeResult nodeResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (nodeResult != null && nodeResult.payload() != null) {
            payload.putAll(nodeResult.payload());
        }
        payload.put("phase", Optional.ofNullable(nodeResult).map(RewardNodeResult::phase).orElse("ERROR"));
        payload.put("terminal", Optional.ofNullable(nodeResult).map(RewardNodeResult::terminal).orElse(Boolean.TRUE));
        payload.putIfAbsent("toolCode", TOOL_NAME);
        payload.putIfAbsent("artifactCode", TOOL_NAME);
        payload.putIfAbsent("kind", "RESULT");
        if ("FORM".equals(payload.get("kind"))) {
            payload.putIfAbsent("status", isConfirmMode(payload) ? "WAITING_CONFIRMATION" : "WAITING_INPUT");
            payload.putIfAbsent("summary", Map.of());
        }
        else {
            boolean success = !Boolean.FALSE.equals(payload.get("success"))
                    && !StringUtils.hasText(asText(payload.get("error")));
            if (!success) {
                String detail = firstText(payload.get("error"), payload.get("message"));
                if (StringUtils.hasText(detail)) {
                    payload.putIfAbsent("error", detail);
                    payload.putIfAbsent("message", detail);
                }
            }
        }
        return payload;
    }

    private boolean isConfirmMode(Map<String, Object> payload) {
        return "CONFIRM".equalsIgnoreCase(firstText(payload.get("mode"), payload.get("status")));
    }

    private boolean shouldStartFreshRequest(String userInput, PendingRewardForm pendingRewardForm) {
        return pendingRewardForm.active()
                && isRewardRequest(userInput)
                && !isConfirmText(userInput);
    }

    private Map<String, Object> errorPayload(String message) {
        return new LinkedHashMap<>(Map.of(
                "kind", "RESULT",
                "success", false,
                "message", message,
                "error", message,
                "toolCode", TOOL_NAME,
                "artifactCode", TOOL_NAME));
    }

    private PendingRewardForm resolvePendingRewardForm(@Nullable ToolContext toolContext) {
        OverAllState state = extractState(toolContext);
        if (state == null) {
            return PendingRewardForm.inactive();
        }
        Object rawThreadState = state.value(AssistantStateKeys.FRONTEND_THREAD_STATE, Object.class).orElse(null);
        if (!(rawThreadState instanceof Map<?, ?> threadState)) {
            return PendingRewardForm.inactive();
        }
        Object rawPendingForm = threadState.get("pendingForm");
        if (!(rawPendingForm instanceof Map<?, ?> pendingForm)) {
            return PendingRewardForm.inactive();
        }
        String toolCode = asText(pendingForm.get("toolCode"));
        if (!TOOL_NAME.equals(toolCode) || Boolean.TRUE.equals(pendingForm.get("readOnly"))) {
            return PendingRewardForm.inactive();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        Object rawValues = pendingForm.get("values");
        if (rawValues instanceof Map<?, ?> currentValues) {
            currentValues.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }
        String mode = asText(pendingForm.get("mode"));
        boolean confirming = "CONFIRM".equalsIgnoreCase(mode)
                || "WAITING_CONFIRMATION".equalsIgnoreCase(asText(pendingForm.get("status")));
        return new PendingRewardForm(true, confirming, values);
    }

    private OverAllState extractState(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return rawState instanceof OverAllState overAllState ? overAllState : null;
    }

    private boolean isRewardRequest(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        return userInput.contains("奖励")
                || userInput.contains("惩罚")
                || userInput.contains("处罚")
                || (userInput.contains("生日")
                && (userInput.contains("给") || userInput.contains("发") || userInput.contains("福利")));
    }

    private boolean isConfirmText(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        return userInput.contains("确认")
                || userInput.contains("提交")
                || userInput.contains("确定")
                || userInput.contains("可以");
    }

    private Integer firstInteger(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            String text = asText(value);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                return Integer.parseInt(text);
            }
            catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return null;
    }

    private BigDecimal firstDecimal(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            String text = asText(value);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                return new BigDecimal(text);
            }
            catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return null;
    }

    private LocalDate firstDate(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof LocalDate localDate) {
                return localDate;
            }
            String text = asText(value);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                return LocalDate.parse(text);
            }
            catch (Exception ignored) {
                // ignore
            }
        }
        return null;
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
                .description("员工奖惩流程工具，统一返回前端表单，员工信息必须通过 DataAgent 查询")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "userInput": {
                              "type": "string",
                              "description": "用户原始输入，例如：我要奖励张三、给这个月过生日的人奖励200元"
                            },
                            "confirmed": {
                              "type": "boolean",
                              "description": "是否已经确认提交表单"
                            }
                          },
                          "required": ["userInput"]
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("reward_tools")
                .targetClassDescription("员工奖惩流程工具集合")
                .fewShots(List.of(
                        new CodeExample(
                                "reward form",
                                "result = reward_workflow(userInput='我要奖励张三')",
                                "返回奖惩表单"),
                        new CodeExample(
                                "birthday reward form",
                                "result = reward_workflow(userInput='给这个月过生日的人奖励200元')",
                                "返回预填金额的奖惩表单")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }

    private record PendingRewardForm(boolean active, boolean confirming, Map<String, Object> values) {

        private PendingRewardForm {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        private static PendingRewardForm inactive() {
            return new PendingRewardForm(false, false, Map.of());
        }
    }
}
