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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds a JSON-only extraction contract when a form action is locked to runtime-controlled slot collection.
 *
 * <p>The LLM may infer current-turn slot values and draft a natural follow-up message, but runtime still owns
 * actual tool selection and invokes {@code slot_collect} after validating the model output.</p>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class FormFlowExtractionContributor implements PromptContributor {

    private final RuntimeConfigView runtimeConfigView;
    private final ObjectMapper objectMapper;

    public FormFlowExtractionContributor(RuntimeConfigView runtimeConfigView) {
        this(runtimeConfigView, new ObjectMapper());
    }

    @Autowired
    public FormFlowExtractionContributor(RuntimeConfigView runtimeConfigView, ObjectMapper objectMapper) {
        this.runtimeConfigView = runtimeConfigView;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "form-flow-extraction";
    }

    @Override
    public int getPriority() {
        return 160;
    }

    @Override
    public boolean shouldContribute(PromptContributorContext context) {
        return runtimeConfigView.promptDynamicEnabled()
                && context != null
                && context.getAttributes() != null
                && Boolean.TRUE.equals(context.getAttributes().get(AssistantStateKeys.FORM_FLOW_EXTRACTION_PENDING))
                && hasActionMeta(context.getAttributes().get(AssistantStateKeys.MATCHED_TOOL_META));
    }

    @Override
    public PromptContribution contribute(PromptContributorContext context) {
        if (context == null || context.getAttributes() == null) {
            return PromptContribution.empty();
        }
        Map<String, Object> actionMeta = normalizeActionMeta(context.getAttributes().get(AssistantStateKeys.MATCHED_TOOL_META));
        String toolCode = asText(actionMeta.get("toolCode"));
        String toolName = asText(actionMeta.get("toolName"));
        String actionLabel = StringUtils.hasText(toolName) ? toolName : toolCode;
        if (!StringUtils.hasText(actionLabel)) {
            return PromptContribution.empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【表单槽位提取任务】\n");
        sb.append("当前动作已由运行时锁定，本轮不要决定流程，也不要调用任何工具。\n");
        sb.append("动作：").append(actionLabel).append("\n");
        if (StringUtils.hasText(toolCode)) {
            sb.append("toolCode=").append(toolCode).append("\n");
        }
        sb.append("你的唯一任务：\n");
        sb.append("1. 只基于最新一轮用户输入提取本轮明确给出的槽位，放入 extractedSlots\n");
        sb.append("2. 生成一段面向用户的自然中文追问或确认引导，放入 displayMessage\n");
        sb.append("3. 不要补默认值，不要猜身份类字段，不要决定是否执行\n");
        sb.append("4. 忽略其他 React 工具调用指令，本轮只输出 JSON\n");
        sb.append("输出格式：\n");
        sb.append("{\"extractedSlots\": {\"slot_name\": \"value\"}, \"displayMessage\": \"...\"}\n");
        sb.append("不要输出 Markdown，不要解释，不要调用工具。\n");

        return PromptContribution.builder()
                .append(new UserMessage(sb.toString()))
                .build();
    }

    private boolean hasActionMeta(Object rawMeta) {
        Map<String, Object> normalized = normalizeActionMeta(rawMeta);
        return StringUtils.hasText(asText(normalized.get("toolCode")))
                || StringUtils.hasText(asText(normalized.get("toolName")));
    }

    private Map<String, Object> normalizeActionMeta(Object rawMeta) {
        if (rawMeta == null) {
            return Collections.emptyMap();
        }
        if (rawMeta instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), value);
                }
            });
            return normalized;
        }
        try {
            return objectMapper.convertValue(rawMeta, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (IllegalArgumentException ex) {
            return Collections.emptyMap();
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
