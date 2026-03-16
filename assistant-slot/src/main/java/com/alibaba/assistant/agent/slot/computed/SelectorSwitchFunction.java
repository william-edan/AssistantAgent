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
package com.alibaba.assistant.agent.slot.computed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 通用选择器切换函数。
 * 根据 selector 当前值，从 cases 中选择对应结果，可用于按类型切换提交字段形态。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class SelectorSwitchFunction implements ComputedFunction {

    @Override
    public String getName() {
        return "selector_switch";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> params, ComputationContext context) throws ComputationException {
        String selectorKey = asText(params.get("selector"));
        if (!StringUtils.hasText(selectorKey)) {
            throw new ComputationException("selector_switch requires selector");
        }
        Object selectorValue = context.getValue(selectorKey);
        if (selectorValue == null) {
            throw new ComputationException("Cannot resolve selector value for: " + selectorKey);
        }
        Object casesObj = params.get("cases");
        if (!(casesObj instanceof Map<?, ?> rawCases) || rawCases.isEmpty()) {
            throw new ComputationException("selector_switch requires non-empty cases");
        }

        Map<String, Object> cases = (Map<String, Object>) rawCases;
        String selectorText = String.valueOf(selectorValue);
        Object chosen = cases.containsKey(selectorText) ? cases.get(selectorText) : params.get("default");
        return resolveResult(chosen, context);
    }

    @Override
    public boolean validate(Map<String, Object> params) {
        return params != null && params.containsKey("selector") && params.containsKey("cases");
    }

    private Object resolveResult(Object chosen, ComputationContext context) {
        if (chosen == null) {
            return null;
        }
        if (chosen instanceof String text) {
            String trimmed = text.trim();
            if (!StringUtils.hasText(trimmed)) {
                return "";
            }
            Object contextValue = context.getValue(trimmed);
            return contextValue != null ? contextValue : trimmed;
        }
        return chosen;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
