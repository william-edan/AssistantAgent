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
 * 通用日期范围展示函数。
 * 当开始和结束日期相同且允许折叠时，仅返回单日期；否则返回完整区间文案。
 */
@Component
public class DateRangeLabelFunction implements ComputedFunction {

    @Override
    public String getName() {
        return "date_range_label";
    }

    @Override
    public Object execute(Map<String, Object> params, ComputationContext context) throws ComputationException {
        String startKey = asText(params.get("start"));
        String endKey = asText(params.get("end"));
        String separator = resolveSeparator(params.get("separator"));
        boolean collapseSameDay = Boolean.parseBoolean(String.valueOf(params.getOrDefault("collapse_same_day", true)));
        if (!StringUtils.hasText(startKey) || !StringUtils.hasText(endKey)) {
            throw new ComputationException("date_range_label requires start and end parameters");
        }

        String startValue = resolveValue(startKey, context);
        String endValue = resolveValue(endKey, context);
        if (!StringUtils.hasText(startValue) || !StringUtils.hasText(endValue)) {
            throw new ComputationException("Cannot resolve start or end value");
        }
        if (collapseSameDay && startValue.equals(endValue)) {
            return startValue;
        }
        return startValue + (separator != null ? separator : " ~ ") + endValue;
    }

    @Override
    public boolean validate(Map<String, Object> params) {
        return params.containsKey("start") && params.containsKey("end");
    }

    private String resolveValue(String key, ComputationContext context) {
        Object value = context.getValue(key);
        if (value != null) {
            return String.valueOf(value);
        }
        return key;
    }

    private String resolveSeparator(Object value) {
        if (value == null) {
            return " ~ ";
        }
        return String.valueOf(value);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
