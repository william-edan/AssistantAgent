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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;

/**
 * 通用周期预设函数。
 * 根据枚举值和锚点日期，推导日/周/月等周期的开始或结束日期。
 */
@Component
public class DatePeriodPresetFunction implements ComputedFunction {

    @Override
    public String getName() {
        return "period_preset";
    }

    @Override
    public Object execute(Map<String, Object> params, ComputationContext context) throws ComputationException {
        String selectorKey = asText(params.get("selector"));
        String target = normalizeTarget(asText(params.get("target")));
        String anchorKey = asText(params.getOrDefault("anchor", "current_date"));
        Object presetsRaw = params.get("presets");
        if (!StringUtils.hasText(selectorKey) || !StringUtils.hasText(target) || !(presetsRaw instanceof Map<?, ?> presets)) {
            throw new ComputationException("period_preset requires selector, target and presets");
        }

        Object selectorValue = context.getValue(selectorKey);
        if (selectorValue == null) {
            throw new ComputationException("Selector value is missing: " + selectorKey);
        }

        String preset = resolvePreset(presets, selectorValue);
        if (!StringUtils.hasText(preset)) {
            throw new ComputationException("Preset mapping not found for selector value: " + selectorValue);
        }

        LocalDate anchorDate = resolveAnchorDate(anchorKey, context);
        LocalDate[] range = resolveRange(anchorDate, preset);
        return "start".equals(target) ? range[0].toString() : range[1].toString();
    }

    @Override
    public boolean validate(Map<String, Object> params) {
        return params.containsKey("selector") && params.containsKey("target") && params.containsKey("presets");
    }

    private String normalizeTarget(String rawTarget) {
        if (!StringUtils.hasText(rawTarget)) {
            return null;
        }
        String normalized = rawTarget.trim().toLowerCase(Locale.ROOT);
        return ("start".equals(normalized) || "end".equals(normalized)) ? normalized : null;
    }

    private String resolvePreset(Map<?, ?> presets, Object selectorValue) {
        String rawValue = asText(selectorValue);
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        Object direct = presets.get(rawValue);
        if (direct == null) {
            for (Map.Entry<?, ?> entry : presets.entrySet()) {
                if (entry.getKey() != null && rawValue.equalsIgnoreCase(String.valueOf(entry.getKey()).trim())) {
                    direct = entry.getValue();
                    break;
                }
            }
        }
        return asText(direct);
    }

    private LocalDate resolveAnchorDate(String anchorKey, ComputationContext context) throws ComputationException {
        Object anchorValue = context.getValue(anchorKey);
        if (anchorValue == null && StringUtils.hasText(anchorKey) && anchorKey.matches("\\d{4}-\\d{2}-\\d{2}")) {
            anchorValue = anchorKey;
        }
        if (anchorValue == null) {
            throw new ComputationException("Anchor date is missing: " + anchorKey);
        }
        try {
            return LocalDate.parse(String.valueOf(anchorValue), DateTimeFormatter.ISO_LOCAL_DATE);
        }
        catch (DateTimeParseException ex) {
            throw new ComputationException("Invalid anchor date: " + anchorValue, ex);
        }
    }

    private LocalDate[] resolveRange(LocalDate anchorDate, String preset) throws ComputationException {
        String normalized = preset.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DAY", "CURRENT_DAY" -> new LocalDate[] {anchorDate, anchorDate};
            case "WEEK", "CURRENT_WEEK" -> {
                LocalDate start = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new LocalDate[] {start, start.plusDays(6)};
            }
            case "MONTH", "CURRENT_MONTH" -> {
                LocalDate start = anchorDate.withDayOfMonth(1);
                yield new LocalDate[] {start, anchorDate.with(TemporalAdjusters.lastDayOfMonth())};
            }
            default -> throw new ComputationException("Unsupported period preset: " + preset);
        };
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
