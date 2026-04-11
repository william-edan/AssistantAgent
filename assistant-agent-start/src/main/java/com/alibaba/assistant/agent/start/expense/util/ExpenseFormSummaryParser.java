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
package com.alibaba.assistant.agent.start.expense.util;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报销表单自然语言摘要解析器。
 *
 * <p>支持两类输入：</p>
 * <p>1) 非结构化口语：如“报销交通费100元”；</p>
 * <p>2) 标签式自然语言：如“报销主体 A，关联项目 B，报销明细 报销类型 住宿费, 金额 100; 报销类型 招待费, 金额 200”。</p>
 */
public final class ExpenseFormSummaryParser {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\b([A-Za-z]{1,6}\\d{6,})\\b");

    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{1,2}-\\d{1,2})");

    private static final Pattern MONTH_PATTERN = Pattern.compile("(20\\d{2}-\\d{1,2})(?!-\\d{1,2})");

    private static final Pattern LABELED_TYPE_PATTERN = Pattern.compile("(?:报销类型|费用类型|类型)\\s*[:：=]?\\s*([^,，;；\\n]+)");

    private static final Pattern LABELED_AMOUNT_PATTERN = Pattern.compile("(?:报销金额|费用金额|金额)\\s*[:：=]?\\s*(\\d+(?:\\.\\d{1,2})?)");

    private static final Pattern LABELED_REMARKS_PATTERN = Pattern.compile("(?:备注|说明)\\s*[:：=]?\\s*([^,，;；\\n]+)");

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)");

    private ExpenseFormSummaryParser() {
    }

    /**
     * 从用户输入中提取可直接预填表单的核心字段。
     */
    public static ParsedExpenseSummary extract(@Nullable String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return ParsedExpenseSummary.empty();
        }

        String normalizedInput = userInput.trim();
        Map<String, String> rawValues = extractRawValues(normalizedInput);
        List<DetailSummary> details = parseDetails(normalizedInput);

        String code = firstText(rawValues.get("code"), firstMatch(CODE_PATTERN, normalizedInput, 1));
        String expenseTime = firstText(
                normalizeDate(rawValues.get("expense_time")),
                normalizeDate(firstMatch(DATE_PATTERN, normalizedInput, 1)));
        String incomeMonth = firstText(
                normalizeMonth(rawValues.get("income_month")),
                normalizeMonth(firstMatch(MONTH_PATTERN, normalizedInput, 1)));

        if (details.isEmpty()) {
            String detailAmount = normalizeAmount(firstText(
                    rawValues.get("detail_amount"),
                    firstMatch(AMOUNT_PATTERN, normalizedInput, 1)));
            String detailCategory = rawValues.get("detail_category");
            String detailRemarks = rawValues.get("detail_remarks");

            String looseDetailText = extractLooseDetailText(normalizedInput, detailAmount);
            if (!StringUtils.hasText(detailCategory)) {
                detailCategory = looseDetailText;
            }
            if (!StringUtils.hasText(detailRemarks)) {
                detailRemarks = looseDetailText;
            }
            if (StringUtils.hasText(detailCategory)
                    || StringUtils.hasText(detailAmount)
                    || StringUtils.hasText(detailRemarks)) {
                details = List.of(new DetailSummary(detailCategory, detailAmount, detailRemarks));
            }
        }

        return new ParsedExpenseSummary(
                code,
                expenseTime,
                incomeMonth,
                rawValues.get("subject_id"),
                rawValues.get("project_id"),
                rawValues.get("applicant"),
                rawValues.get("approver"),
                rawValues.get("copy_users"),
                rawValues.get("flow"),
                details);
    }

    /**
     * 把“标签 + 值”的自然语言分段抽取成原始字段。
     */
    private static Map<String, String> extractRawValues(String userInput) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!StringUtils.hasText(userInput)) {
            return values;
        }

        for (String rawSegment : userInput.split("[,，;；\\n]")) {
            String segment = normalizeSegment(rawSegment);
            if (!StringUtils.hasText(segment)) {
                continue;
            }

            putIfHasText(values, "subject_id", extractValue(segment, List.of("报销主体", "主体")));
            putIfHasText(values, "project_id", extractValue(segment, List.of("关联项目", "项目")));
            putIfHasText(values, "applicant", extractValue(segment, List.of("报销人", "申请人")));
            putIfHasText(values, "approver", extractValue(segment, List.of("审批人", "审核人")));
            putIfHasText(values, "copy_users", extractValue(segment, List.of("抄送人")));
            putIfHasText(values, "flow", extractValue(segment, List.of("审批流程", "审批流", "流程")));
            putIfHasText(values, "expense_time", extractValue(segment, List.of("报销日期", "日期")));
            putIfHasText(values, "income_month", extractValue(segment, List.of("所属月份", "月份")));
            putIfHasText(values, "code", extractValue(segment, List.of("凭证编号", "编号", "单号")));
            putIfHasText(values, "detail_category", extractValue(segment, List.of("报销类型", "费用类型", "类型")));
            putIfHasText(values, "detail_amount", extractValue(segment, List.of("报销金额", "费用金额", "金额")));
            putIfHasText(values, "detail_remarks", extractValue(segment, List.of("备注", "说明")));
        }

        if (StringUtils.hasText(values.get("detail_amount"))) {
            values.put("detail_amount", normalizeAmount(values.get("detail_amount")));
        }
        return values;
    }

    /**
     * 支持多条明细抽取：
     * “报销类型 住宿费, 金额 100; 报销类型 招待费, 金额 200”
     */
    private static List<DetailSummary> parseDetails(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return List.of();
        }
        List<DetailSummary> details = new ArrayList<>();
        for (String rawSegment : userInput.split("[;；\\n]")) {
            String segment = normalizeSegment(rawSegment);
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            String category = firstMatch(LABELED_TYPE_PATTERN, segment, 1);
            String amount = normalizeAmount(firstMatch(LABELED_AMOUNT_PATTERN, segment, 1));
            String remarks = firstMatch(LABELED_REMARKS_PATTERN, segment, 1);
            if (StringUtils.hasText(category) || StringUtils.hasText(amount) || StringUtils.hasText(remarks)) {
                details.add(new DetailSummary(category, amount, remarks));
            }
        }
        return details;
    }

    private static String normalizeSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return null;
        }
        String normalized = segment.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private static String extractValue(String segment, List<String> labels) {
        if (!StringUtils.hasText(segment)) {
            return null;
        }
        for (String label : labels) {
            if (!segment.startsWith(label)) {
                continue;
            }
            String value = segment.substring(label.length()).trim();
            while (StringUtils.hasText(value)
                    && (value.startsWith(":")
                    || value.startsWith("：")
                    || value.startsWith("="))) {
                value = value.substring(1).trim();
            }
            return StringUtils.hasText(value) ? value : null;
        }
        return null;
    }

    private static String extractLooseDetailText(String text, @Nullable String amount) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String detailText = text
                .replaceAll("^(我想|我要|帮我|请|麻烦)?", "")
                .replaceAll("^(提交|添加|新增|发起)?", "")
                .replaceAll("报销(申请)?", "")
                .trim();
        if (StringUtils.hasText(amount)) {
            detailText = detailText.replaceFirst(Pattern.quote(amount), "");
            detailText = detailText.replaceAll("(元|块|rmb)$", "");
        }
        detailText = detailText.replaceAll("^[,，。\\s]+", "").trim();
        return StringUtils.hasText(detailText) ? detailText : null;
    }

    private static String normalizeAmount(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = AMOUNT_PATTERN.matcher(value);
        if (!matcher.find()) {
            return value.trim();
        }
        return matcher.group(1);
    }

    private static String normalizeDate(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(20\\d{2})-(\\d{1,2})-(\\d{1,2})").matcher(value.trim());
        if (!matcher.find()) {
            return value.trim();
        }
        return String.format(
                Locale.ROOT,
                "%s-%02d-%02d",
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    private static String normalizeMonth(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(20\\d{2})-(\\d{1,2})").matcher(value.trim());
        if (!matcher.find()) {
            return value.trim();
        }
        return String.format(Locale.ROOT, "%s-%02d", matcher.group(1), Integer.parseInt(matcher.group(2)));
    }

    private static String firstMatch(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(group);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static void putIfHasText(Map<String, String> values, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value.trim());
        }
    }

    /**
     * 解析后的报销摘要。
     */
    public record ParsedExpenseSummary(
            String code,
            String expenseTime,
            String incomeMonth,
            String subjectId,
            String projectId,
            String applicant,
            String approver,
            String copyUsers,
            String flow,
            List<DetailSummary> details) {

        public static ParsedExpenseSummary empty() {
            return new ParsedExpenseSummary(null, null, null, null, null, null, null, null, null, List.of());
        }
    }

    public record DetailSummary(String category, String amount, String remarks) {
    }
}
