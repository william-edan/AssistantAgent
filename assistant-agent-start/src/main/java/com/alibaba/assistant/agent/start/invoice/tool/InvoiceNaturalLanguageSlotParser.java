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
package com.alibaba.assistant.agent.start.invoice.tool;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开票流程自然语言兜底解析器。
 *
 * <p>用途：当上游 slot 抽取还没有覆盖开票场景时，
 * 直接从用户自然语言里解析金额、抬头、类型、审批人等核心字段。</p>
 */
public final class InvoiceNaturalLanguageSlotParser {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d{1,2})?)\\s*元");

    private static final Pattern MOBILE_PATTERN = Pattern.compile("(1[3-9]\\d{9})");

    private static final Pattern TITLE_AFTER_GEI_PATTERN = Pattern.compile("(?:给|帮|替)([^，。；;,\\s]{2,40}?)(?:开票|开发票|开)");

    private static final List<FieldRule> FIELD_RULES = List.of(
            new FieldRule("amount", "(?:金额|开票金额)\\s*[:：]?\\s*(\\d+(?:\\.\\d{1,2})?)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_subject", "(?:开票主体|主体)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("types", "(?:抬头类型)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::normalizeTitleType),
            // “抬头类型 企业，开票抬头 XXX” 是高频输入，需避免把“抬头类型”误识别成“开票抬头”。
            new FieldRule("invoice_title", "(?:开票抬头|抬头(?!类型))\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_tax", "(?:税号|纳税识别号)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_bank", "(?:开户行)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_account", "(?:银行账号|账号)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_banking", "(?:银行营业点|开户网点|营业点)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_phone", "(?:电话|联系电话)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("invoice_address", "(?:地址|开户地址)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("project_name", "(?:关联项目|项目)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("remark", "(?:备注|说明)\\s*[:：]?\\s*([^\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("flow_id", "(?:审批流程|流程)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("check_uames", "(?:审批人)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue),
            new FieldRule("check_copy_unames", "(?:抄送人)\\s*[:：]?\\s*([^，。；;\\n]+)", InvoiceNaturalLanguageSlotParser::trimValue));

    private InvoiceNaturalLanguageSlotParser() {
    }

    public static Map<String, Object> parse(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldRule rule : FIELD_RULES) {
            rule.extract(userInput).ifPresent(value -> values.put(rule.fieldName(), value));
        }

        putIfAbsent(values, "amount", extractAmount(userInput));
        putIfAbsent(values, "invoice_phone", extractMobile(userInput));
        putIfAbsent(values, "invoice_type", extractInvoiceType(userInput));
        putIfAbsent(values, "invoice_title", extractInvoiceTitle(userInput));

        String title = asText(values.get("invoice_title"));
        putIfAbsent(values, "types", extractTitleType(userInput, title));
        return Map.copyOf(values);
    }

    private static String extractAmount(String userInput) {
        Matcher matcher = AMOUNT_PATTERN.matcher(userInput);
        if (!matcher.find()) {
            return null;
        }
        return trimValue(matcher.group(1));
    }

    private static String extractMobile(String userInput) {
        Matcher matcher = MOBILE_PATTERN.matcher(userInput);
        if (!matcher.find()) {
            return null;
        }
        return trimValue(matcher.group(1));
    }

    private static String extractInvoiceType(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        if (containsAny(userInput, "增值税专票", "增值税专用发票", "专票")) {
            return "1";
        }
        if (containsAny(userInput, "普票", "普通发票")) {
            return "2";
        }
        if (containsAny(userInput, "专用发票")) {
            return "3";
        }
        return null;
    }

    private static String extractInvoiceTitle(String userInput) {
        Matcher matcher = TITLE_AFTER_GEI_PATTERN.matcher(Optional.ofNullable(userInput).orElse(""));
        if (matcher.find()) {
            return trimValue(matcher.group(1));
        }
        return null;
    }

    private static String extractTitleType(String userInput, String title) {
        if (containsAny(userInput, "企业", "公司", "单位")) {
            return "1";
        }
        if (containsAny(userInput, "个人", "个人抬头")) {
            return "2";
        }
        if (looksLikeEnterpriseTitle(title)) {
            return "1";
        }
        return null;
    }

    private static String normalizeTitleType(String rawValue) {
        String text = trimValue(rawValue);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (containsAny(text, "企业", "公司", "单位")) {
            return "1";
        }
        if (containsAny(text, "个人")) {
            return "2";
        }
        return null;
    }

    private static boolean looksLikeEnterpriseTitle(String title) {
        return containsAny(title, "公司", "有限", "集团", "科技", "贸易", "中心", "事务所", "学校", "医院", "银行", "厂");
    }

    private static boolean containsAny(String input, String... keywords) {
        if (!StringUtils.hasText(input) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && input.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static void putIfAbsent(Map<String, Object> values, String key, String value) {
        if (!values.containsKey(key) && StringUtils.hasText(value)) {
            values.put(key, value);
        }
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

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private record FieldRule(String fieldName, Pattern pattern, Function<String, String> transformer) {

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
