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
package com.alibaba.assistant.agent.api.protocol;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 前端消息可见性规则。
 * 用于过滤运行时内部规划说明，避免把工具调用旁白透传给最终用户。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public final class FrontendMessageVisibilitySupport {

    private static final Set<String> INTERNAL_PLANNING_PHRASES = Set.of(
            "我将调用",
            "我将启动槽位收集流程",
            "接下来需通过",
            "对应可用工具为",
            "匹配到可用工具",
            "意图清晰对应工具",
            "需启动槽位收集",
            "需先调用slot_collect",
            "开始槽位收集",
            "继续补充槽位信息",
            "识别为操作请求",
            "根据策略",
            "根据执行策略",
            "当前可用且匹配",
            "不可编造",
            "自动加载该工具所需的 slotschema",
            "系统将自动加载其槽位定义",
            "识别当前缺失的必填字段",
            "填充必要元信息",
            "填入占位值",
            "用户已确认，执行操作",
            "missing required slots, continue collecting");

    private static final Set<String> INTERNAL_PROTOCOL_MARKERS = Set.of(
            "slot_collect",
            "slot_confirm",
            "artifact_execute",
            "toolcode",
            "tool code",
            "slotschema");

    private FrontendMessageVisibilitySupport() {
    }

    /**
     * 判断文本是否属于内部规划说明。
     */
    public static boolean isInternalPlanningNarration(String text) {
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        boolean hasPlanningPhrase = containsAny(normalized, INTERNAL_PLANNING_PHRASES);
        boolean hasProtocolMarker = containsAny(normalized, INTERNAL_PROTOCOL_MARKERS);
        return hasPlanningPhrase || (hasProtocolMarker && normalized.contains("工具"));
    }

    /**
     * 判断文本是否适合直接展示给前端用户。
     */
    public static boolean isVisibleAssistantText(String text) {
        return StringUtils.hasText(text) && !isInternalPlanningNarration(text);
    }

    private static boolean containsAny(String normalized, Set<String> phrases) {
        if (!StringUtils.hasText(normalized) || phrases == null || phrases.isEmpty()) {
            return false;
        }
        for (String phrase : phrases) {
            if (StringUtils.hasText(phrase) && normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
