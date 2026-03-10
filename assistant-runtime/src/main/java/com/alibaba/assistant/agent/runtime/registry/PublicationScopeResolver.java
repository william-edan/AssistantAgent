/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves runtime publication scope from ToolContext and prompt/runtime attributes.
 */
@Component
public class PublicationScopeResolver {

    private static final String DEFAULT_TENANT = "default";

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final PlatformSpaceService platformSpaceService;

    private final AgentAppPublicationPolicyResolver agentAppPublicationPolicyResolver;

    public PublicationScopeResolver(PlatformSpaceService platformSpaceService) {
        this(platformSpaceService, null);
    }

    public PublicationScopeResolver(
            PlatformSpaceService platformSpaceService,
            @Nullable AgentAppPublicationPolicyResolver agentAppPublicationPolicyResolver) {
        this.platformSpaceService = platformSpaceService;
        this.agentAppPublicationPolicyResolver = agentAppPublicationPolicyResolver;
    }

    /**
     * Resolve publication scope for the current tool execution context.
     */
    public ToolPublicationProvider.PublicationScope resolve(@Nullable ToolContext toolContext) {
        Map<String, Object> context = toolContext != null && toolContext.getContext() != null
                ? toolContext.getContext() : Map.of();
        return resolveFromContext(context);
    }

    /**
     * Resolve publication scope from attribute maps used by prompt contributors and controllers.
     */
    public ToolPublicationProvider.PublicationScope resolve(@Nullable Map<String, Object> attributes) {
        return resolveFromContext(attributes != null ? attributes : Map.of());
    }

    private ToolPublicationProvider.PublicationScope resolveFromContext(Map<String, Object> context) {
        OverAllState state = extractState(context);
        String tenantId = firstNonBlank(
                readStateText(state, "tenant_id"),
                readStateText(state, "tenantId"),
                readText(context, "tenant_id", "tenantId"),
                DEFAULT_TENANT);
        String environment = firstNonBlank(
                readStateText(state, AssistantStateKeys.SPACE_ENVIRONMENT),
                readStateText(state, "environment"),
                readText(context, AssistantStateKeys.SPACE_ENVIRONMENT, "environment"),
                DEFAULT_ENVIRONMENT);
        String agentAppCode = firstNonBlank(
                readStateText(state, AssistantStateKeys.AGENT_APP_CODE),
                readStateText(state, "agentAppCode"),
                readStateText(state, "appName"),
                readText(context, AssistantStateKeys.AGENT_APP_CODE, "agent_app_code", "agentAppCode", "appName"));
        Long spaceId = firstNonNull(
                readStateLong(state, AssistantStateKeys.SPACE_ID),
                readStateLong(state, "spaceId"),
                asLong(readValue(context, AssistantStateKeys.SPACE_ID, "space_id", "spaceId")));
        String spaceCode = firstNonBlank(
                readStateText(state, AssistantStateKeys.SPACE_CODE),
                readStateText(state, "spaceCode"),
                readText(context, AssistantStateKeys.SPACE_CODE, "space_code", "spaceCode"),
                tenantId);
        if (spaceId == null && StringUtils.hasText(spaceCode)) {
            Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode, environment);
            spaceId = space.map(PlatformSpace::getId).orElse(null);
        }

        String explicitSourceSelectionMode = firstNonBlank(
                readStateText(state, "tool_source_mode"),
                readStateText(state, "toolSourceMode"),
                readText(context, "tool_source_mode", "toolSourceMode", "publication_source_mode"));
        List<String> explicitRequestedSourceIds = firstNonEmptyList(
                readStateTextList(state, "tool_source_ids"),
                readStateTextList(state, "toolSourceIds"),
                readTextList(context, "tool_source_ids", "toolSourceIds", "publicationSourceIds"),
                List.of());
        List<String> explicitBlockedSourceIds = firstNonEmptyList(
                readStateTextList(state, "disabled_tool_source_ids"),
                readStateTextList(state, "disabledToolSourceIds"),
                readTextList(context, "disabled_tool_source_ids", "disabledToolSourceIds", "blockedSourceIds"),
                List.of());
        boolean hasExplicitSourceSelection = StringUtils.hasText(explicitSourceSelectionMode)
                || !explicitRequestedSourceIds.isEmpty()
                || !explicitBlockedSourceIds.isEmpty();
        AgentAppPublicationPolicyResolver.PublicationSourcePolicy appDefaultPolicy = null;
        if (!hasExplicitSourceSelection && agentAppPublicationPolicyResolver != null) {
            appDefaultPolicy = agentAppPublicationPolicyResolver.resolve(spaceId, agentAppCode).orElse(null);
        }
        ToolPublicationProvider.SourceSelectionMode sourceSelectionMode = hasExplicitSourceSelection
                ? ToolPublicationProvider.SourceSelectionMode.fromValue(explicitSourceSelectionMode)
                : appDefaultPolicy != null
                        ? appDefaultPolicy.sourceSelectionMode()
                        : ToolPublicationProvider.SourceSelectionMode.MERGE;
        List<String> requestedSourceIds = hasExplicitSourceSelection
                ? explicitRequestedSourceIds
                : appDefaultPolicy != null ? appDefaultPolicy.requestedSourceIds() : List.of();
        List<String> blockedSourceIds = hasExplicitSourceSelection
                ? explicitBlockedSourceIds
                : appDefaultPolicy != null ? appDefaultPolicy.blockedSourceIds() : List.of();

        return new ToolPublicationProvider.PublicationScope(
                tenantId,
                spaceId,
                environment,
                agentAppCode,
                sourceSelectionMode,
                requestedSourceIds,
                blockedSourceIds);
    }

    private OverAllState extractState(Map<String, Object> context) {
        Object state = context.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return state instanceof OverAllState overAllState ? overAllState : null;
    }

    private String readStateText(@Nullable OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = state.value(key, Object.class).orElse(null);
        return asText(value);
    }

    private List<String> readStateTextList(@Nullable OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return List.of();
        }
        Object value = state.value(key, Object.class).orElse(null);
        return asTextList(value);
    }

    private Long readStateLong(@Nullable OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = state.value(key, Object.class).orElse(null);
        return asLong(value);
    }

    private String readText(Map<String, Object> source, String... keys) {
        return asText(readValue(source, keys));
    }

    private List<String> readTextList(Map<String, Object> source, String... keys) {
        return asTextList(readValue(source, keys));
    }

    private Object readValue(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (StringUtils.hasText(key) && source.containsKey(key)) {
                return source.get(key);
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

    private List<String> asTextList(Object value) {
        if (value == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (value instanceof String text) {
            for (String token : text.split(",")) {
                String normalized = asText(token);
                if (StringUtils.hasText(normalized)) {
                    values.add(normalized);
                }
            }
        }
        else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String normalized = asText(item);
                if (StringUtils.hasText(normalized)) {
                    values.add(normalized);
                }
            }
        }
        else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                String normalized = asText(Array.get(value, i));
                if (StringUtils.hasText(normalized)) {
                    values.add(normalized);
                }
            }
        }
        else {
            String normalized = asText(value);
            if (StringUtils.hasText(normalized)) {
                values.add(normalized);
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(values));
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
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

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
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

    @SafeVarargs
    private final <T> List<T> firstNonEmptyList(List<T>... candidates) {
        if (candidates == null) {
            return List.of();
        }
        for (List<T> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return List.of();
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
}
