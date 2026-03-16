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
package com.alibaba.assistant.agent.runtime.context;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * 统一解析运行时空间上下文，确保聊天、发布和执行链路使用同一套空间解析规则。
 */
@Component
public class RuntimeSpaceResolver {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final PlatformSpaceService platformSpaceService;

    private final String defaultEnvironment;

    public RuntimeSpaceResolver(
            PlatformSpaceService platformSpaceService,
            @Value("${assistant.chat.default-space-environment:prod}") @Nullable String defaultEnvironment) {
        this.platformSpaceService = platformSpaceService;
        this.defaultEnvironment = StringUtils.hasText(defaultEnvironment)
                ? defaultEnvironment.trim()
                : DEFAULT_ENVIRONMENT;
    }

    /**
     * 从普通属性映射中解析空间上下文。
     */
    public ResolvedSpace resolve(@Nullable Map<String, Object> attributes) {
        return resolveFromContext(attributes != null ? attributes : Map.of());
    }

    /**
     * 从工具上下文中解析空间上下文。
     */
    public ResolvedSpace resolve(@Nullable ToolContext toolContext) {
        Map<String, Object> context = toolContext != null && toolContext.getContext() != null
                ? toolContext.getContext()
                : Map.of();
        return resolveFromContext(context);
    }

    private ResolvedSpace resolveFromContext(Map<String, Object> context) {
        OverAllState state = extractState(context);
        String environment = firstNonBlank(
                asText(readValue(context, AssistantStateKeys.SPACE_ENVIRONMENT, "space_environment", "spaceEnvironment", "environment")),
                readStateText(state, AssistantStateKeys.SPACE_ENVIRONMENT),
                readStateText(state, "space_environment"),
                readStateText(state, "spaceEnvironment"),
                readStateText(state, "environment"),
                defaultEnvironment,
                DEFAULT_ENVIRONMENT);
        Long spaceId = firstNonNull(
                asLong(readValue(context, AssistantStateKeys.SPACE_ID, "space_id", "spaceId")),
                readStateLong(state, AssistantStateKeys.SPACE_ID),
                readStateLong(state, "space_id"),
                readStateLong(state, "spaceId"));
        String spaceCode = firstNonBlank(
                asText(readValue(context, AssistantStateKeys.SPACE_CODE, "space_code", "spaceCode")),
                readStateText(state, AssistantStateKeys.SPACE_CODE),
                readStateText(state, "space_code"),
                readStateText(state, "spaceCode"));

        if (spaceId == null && StringUtils.hasText(spaceCode)) {
            Optional<PlatformSpace> platformSpace = platformSpaceService.findActiveByCode(spaceCode, environment);
            if (platformSpace.isPresent()) {
                spaceId = platformSpace.get().getId();
                spaceCode = platformSpace.get().getSpaceCode();
            }
        }

        if (spaceId == null && !StringUtils.hasText(spaceCode)) {
            Optional<PlatformSpace> defaultSpace = platformSpaceService.resolveDefaultRuntimeSpace(environment);
            if (defaultSpace.isPresent()) {
                spaceId = defaultSpace.get().getId();
                spaceCode = defaultSpace.get().getSpaceCode();
            }
        }
        return new ResolvedSpace(spaceId, spaceCode, environment);
    }

    private OverAllState extractState(Map<String, Object> context) {
        Object rawState = context.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return rawState instanceof OverAllState overAllState ? overAllState : null;
    }

    private Object readValue(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            if (source.containsKey(key)) {
                return source.get(key);
            }
        }
        return null;
    }

    private String readStateText(@Nullable OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = state.value(key, Object.class).orElse(null);
        return asText(value);
    }

    private Long readStateLong(@Nullable OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = state.value(key, Object.class).orElse(null);
        return asLong(value);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 运行时解析出的标准空间上下文。
     */
    public record ResolvedSpace(Long spaceId, String spaceCode, String environment) {
    }
}
