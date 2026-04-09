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

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用章流程的自然语言兜底字段解析器。
 *
 * <p>当上游 slot 抽取不完整时，从用户输入中按“字段名 + 值”模式补齐关键槽位，
 * 保障“自然语言一次性填写表单”可以直达提交流程。</p>
 */
final class SealNaturalLanguageSlotParser {

    private static final List<FieldRule> FIELD_RULES = List.of(
            new FieldRule(
                    "title",
                    "(?:申请主题|主题)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "num",
                    "(?:盖章次数|数量|用章数量)\\s*[:：]?\\s*(\\d+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "use_time",
                    "(?:预期用印日期|预期用章日期|用章日期|用印日期)\\s*[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "seal_cate_id",
                    "(?:印章类型|用章类型)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "is_borrow",
                    "(?:印章是否外借|是否外借|是否借出)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::normalizeBorrowFlag),
            new FieldRule(
                    "start_time",
                    "(?:印章借用日期|借用日期|开始借用日期)\\s*[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "end_time",
                    "(?:结束借用日期|借用结束日期|结束日期)\\s*[:：]?\\s*(\\d{4}-\\d{2}-\\d{2})",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "content",
                    "(?:盖章内容|用章内容|说明)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "flow_id",
                    "(?:审批流程|流程)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "check_unames",
                    "(?:审批人)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "check_copy_unames",
                    "(?:抄送人)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue),
            new FieldRule(
                    "did_name",
                    "(?:用章部门|申请部门|部门)\\s*[:：]?\\s*([^，。；;\\n]+)",
                    SealNaturalLanguageSlotParser::trimValue));

    private SealNaturalLanguageSlotParser() {
    }

    static Map<String, Object> parse(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldRule rule : FIELD_RULES) {
            rule.extract(userInput).ifPresent(value -> values.put(rule.fieldName(), value));
        }
        return Map.copyOf(values);
    }

    private static String normalizeBorrowFlag(String rawValue) {
        String text = trimValue(rawValue);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.contains("否") || text.contains("不")) {
            return "0";
        }
        if (text.contains("是")) {
            return "1";
        }
        return text;
    }

    private static String trimValue(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String value = rawValue
                .replace('，', ' ')
                .replace('。', ' ')
                .replace(';', ' ')
                .trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private record FieldRule(
            String fieldName,
            Pattern pattern,
            Function<String, String> transformer) {

        private FieldRule(String fieldName, String regex, Function<String, String> transformer) {
            this(fieldName, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), transformer);
        }

        private Optional<String> extract(String userInput) {
            if (!StringUtils.hasText(userInput)) {
                return Optional.empty();
            }
            Matcher matcher = pattern.matcher(userInput);
            if (!matcher.find() || matcher.groupCount() < 1) {
                return Optional.empty();
            }
            String rawValue = matcher.group(1);
            if (transformer == null) {
                return Optional.ofNullable(rawValue).filter(StringUtils::hasText);
            }
            return Optional.ofNullable(transformer.apply(rawValue)).filter(StringUtils::hasText);
        }
    }
}
