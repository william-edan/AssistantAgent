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
package com.alibaba.assistant.agent.start.department.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.start.department.tool.DepartmentStaffingTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 部门编制与变动查询协议适配策略。
 *
 * <p>负责将工具原始返回结果转换为前端可稳定消费的 `RESULT` 结构，
 * 尤其保证 `finalOutputs`、`sections` 和 `records` 同时存在。</p>
 */
@Component
@Profile("migration")
@Order(126)
public class DepartmentQueryProtocolStrategy implements ProtocolStrategy {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String TEMPLATE_CODE = "PROFILE_CARD";

    private static final String RECORD_TYPE_SINGLE = "single";

    private static final String RECORD_TYPE_LIST = "list";

    private static final String TITLE_FAILED = "部门编制与变动查询失败";

    private final ProtocolPayloadSupport payloadSupport;

    private final ObjectMapper objectMapper;

    public DepartmentQueryProtocolStrategy(ProtocolPayloadSupport payloadSupport, ObjectMapper objectMapper) {
        this.payloadSupport = payloadSupport;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断是否支持当前工具结果。
     *
     * @param normalizedToolName 工具名
     * @param payload 载荷
     * @return 支持时返回 true
     */
    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return DepartmentStaffingTool.TOOL_NAME.equals(normalizedToolName);
    }

    /**
     * 适配为前端事件。
     *
     * @param threadId 线程 ID
     * @param normalizedToolName 工具名
     * @param payload 工具载荷
     * @param state 线程状态
     * @return 前端事件列表
     */
    @Override
    public List<FrontendEvent> adapt(
            String threadId,
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return List.of(payloadSupport.resultEvent(threadId, normalizePayload(payload)));
    }

    /**
     * 投影线程状态。
     *
     * @param normalizedToolName 工具名
     * @param payload 工具载荷
     * @param state 原始状态
     * @return 线程快照
     */
    @Override
    public Map<String, Object> projectThreadState(
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return payloadSupport.projectResultState(normalizePayload(payload), state);
    }

    /**
     * 规范化工具原始载荷。
     *
     * @param payload 原始载荷
     * @return 规范化结果
     */
    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("artifactCode", DepartmentStaffingTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("toolCode", DepartmentStaffingTool.TOOL_NAME);

        String message = firstText(normalizedPayload.get("message"), normalizedPayload.get("reply"));
        if (StringUtils.hasText(message)) {
            normalizedPayload.put("message", message);
        }

        normalizedPayload.put("result", buildResult(normalizedPayload));
        if (Boolean.FALSE.equals(normalizedPayload.get("success"))
                && !normalizedPayload.containsKey("error")
                && StringUtils.hasText(message)) {
            normalizedPayload.put("error", message);
        }
        return normalizedPayload;
    }

    /**
     * 构建前端结果卡主体。
     *
     * @param payload 工具载荷
     * @return 结果卡数据
     */
    private Map<String, Object> buildResult(Map<String, Object> payload) {
        if (!isSuccessful(payload)) {
            return buildFailureResult(payload);
        }

        Map<String, Object> data = asMap(payload.get("data"));
        List<Map<String, Object>> records = extractRecords(data);
        String title = firstText(data.get("queryTitle"), payload.get("query"), "部门编制与变动查询");
        String summary = firstText(data.get("summary"), payload.get("message"), "已查询到部门编制与变动信息。");
        String recordType = records.size() > 1 ? RECORD_TYPE_LIST : RECORD_TYPE_SINGLE;

        Map<String, Object> finalOutputs = buildFinalOutputs(title, records);
        List<Map<String, Object>> sections = buildSections(records);
        List<Map<String, Object>> highlights = buildHighlights(finalOutputs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", TEMPLATE_CODE);
        result.put("title", title);
        result.put("summary", summary);
        result.put("text", summary);
        result.put("recordType", recordType);
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", highlights);
        result.put("sections", sections);
        result.put("records", records);
        result.put("profile", new LinkedHashMap<>(finalOutputs));
        putText(result, "threadId", asText(data.get("threadId")));
        return result;
    }

    /**
     * 构建失败结果卡。
     *
     * @param payload 工具载荷
     * @return 失败结果
     */
    private Map<String, Object> buildFailureResult(Map<String, Object> payload) {
        String errorMessage = firstText(payload.get("error"), payload.get("message"), TITLE_FAILED);

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("结果说明", errorMessage);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", TEMPLATE_CODE);
        result.put("title", TITLE_FAILED);
        result.put("summary", errorMessage);
        result.put("text", errorMessage);
        result.put("recordType", RECORD_TYPE_SINGLE);
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", List.of(buildFieldItem("状态", "查询失败")));
        result.put("sections", List.of(buildSection(
                "failure",
                "失败信息",
                List.of(buildFieldItem("原因", errorMessage)))));
        result.put("records", List.of());
        result.put("profile", Map.of());
        return result;
    }

    /**
     * 从数据节点中提取记录列表。
     *
     * @param data 数据节点
     * @return 记录列表
     */
    private List<Map<String, Object>> extractRecords(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        Object recordsObject = data.get("records");
        if (recordsObject instanceof List<?> rawRecords && !rawRecords.isEmpty()) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Object rawRecord : rawRecords) {
                if (rawRecord instanceof Map<?, ?> recordMap && !recordMap.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> currentRecord = new LinkedHashMap<>((Map<String, Object>) recordMap);
                    records.add(currentRecord);
                }
            }
            if (!records.isEmpty()) {
                return records;
            }
        }

        Map<String, Object> singleRecord = parseRawText(asText(data.get("rawText")));
        return singleRecord.isEmpty() ? List.of() : List.of(singleRecord);
    }

    /**
     * 构建最终摘要字段。
     *
     * @param title 查询标题
     * @param records 记录列表
     * @return 摘要字段
     */
    private Map<String, Object> buildFinalOutputs(String title, List<Map<String, Object>> records) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("查询名称", title);
        finalOutputs.put("记录总数", String.valueOf(records.size()));
        finalOutputs.put("部门总数", String.valueOf(countDepartmentTotal(records)));
        putText(finalOutputs, "总编制人数", sumRecordValue(records, "编制人数", "编制", "核定编制"));
        putText(finalOutputs, "总在岗人数", sumRecordValue(records, "在岗人数", "在岗", "实际人数"));
        putText(finalOutputs, "总入职人数", sumRecordValue(records, "入职人数", "入职", "新增人数", "新增"));
        putText(finalOutputs, "总离职人数", sumRecordValue(records, "离职人数", "离职", "减少人数", "减少"));
        putText(finalOutputs, "总变动人数", sumRecordValue(records, "变动人数", "变动", "净变动"));
        return finalOutputs;
    }

    /**
     * 构建高亮信息。
     *
     * @param finalOutputs 摘要字段
     * @return 高亮列表
     */
    private List<Map<String, Object>> buildHighlights(Map<String, Object> finalOutputs) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        addHighlight(highlights, "部门总数", finalOutputs.get("部门总数"));
        addHighlight(highlights, "总编制人数", finalOutputs.get("总编制人数"));
        addHighlight(highlights, "总入职人数", finalOutputs.get("总入职人数"));
        addHighlight(highlights, "总离职人数", finalOutputs.get("总离职人数"));
        if (highlights.isEmpty()) {
            addHighlight(highlights, "记录总数", finalOutputs.get("记录总数"));
        }
        return highlights;
    }

    /**
     * 构建记录明细分组。
     *
     * @param records 记录列表
     * @return 分组明细
     */
    private List<Map<String, Object>> buildSections(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String value = asText(entry.getValue());
                if (StringUtils.hasText(entry.getKey()) && StringUtils.hasText(value)) {
                    items.add(buildFieldItem(entry.getKey(), value));
                }
            }
            sections.add(buildSection(
                    "department_" + (index + 1),
                    resolveDepartmentName(record, index),
                    items));
        }
        return sections;
    }

    /**
     * 计算部门总数。
     *
     * @param records 记录列表
     * @return 部门总数
     */
    private int countDepartmentTotal(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        Set<String> departmentNames = new LinkedHashSet<>();
        for (int index = 0; index < records.size(); index++) {
            String departmentName = resolveDepartmentName(records.get(index), index);
            if (StringUtils.hasText(departmentName)) {
                departmentNames.add(departmentName);
            }
        }
        return departmentNames.isEmpty() ? records.size() : departmentNames.size();
    }

    /**
     * 按字段别名聚合数值。
     *
     * @param records 记录列表
     * @param aliases 字段别名
     * @return 聚合值
     */
    private String sumRecordValue(List<Map<String, Object>> records, String... aliases) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean matched = false;
        for (Map<String, Object> record : records) {
            String value = resolveRecordValue(record, aliases);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            try {
                total = total.add(new BigDecimal(value.trim()));
                matched = true;
            }
            catch (NumberFormatException ignored) {
                // 非数值字段不参与汇总
            }
        }
        return matched ? total.stripTrailingZeros().toPlainString() : null;
    }

    /**
     * 解析部门名称。
     *
     * @param record 部门记录
     * @param index 索引
     * @return 展示标题
     */
    private String resolveDepartmentName(Map<String, Object> record, int index) {
        return firstText(
                resolveRecordValue(record, "部门名称", "部门", "departmentName", "deptName", "dept_name"),
                "第" + (index + 1) + "个部门");
    }

    /**
     * 根据别名解析记录字段。
     *
     * @param record 记录
     * @param aliases 别名
     * @return 命中的字段值
     */
    private String resolveRecordValue(Map<String, Object> record, String... aliases) {
        if (record == null || record.isEmpty() || aliases == null || aliases.length == 0) {
            return null;
        }
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            for (String alias : aliases) {
                if (normalizeKey(alias).equals(normalizedKey)) {
                    return asText(entry.getValue());
                }
            }
        }
        return null;
    }

    /**
     * 解析原始 JSON 文本。
     *
     * @param rawText 原始文本
     * @return 解析后的 Map
     */
    private Map<String, Object> parseRawText(String rawText) {
        if (!StringUtils.hasText(rawText) || !rawText.trim().startsWith("{")) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(rawText, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        }
        catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * 添加高亮项。
     *
     * @param highlights 高亮列表
     * @param label 标签
     * @param value 值
     */
    private void addHighlight(List<Map<String, Object>> highlights, String label, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(label) && StringUtils.hasText(text)) {
            highlights.add(buildFieldItem(label, text));
        }
    }

    /**
     * 构建字段项。
     *
     * @param label 标签
     * @param value 值
     * @return 字段项
     */
    private Map<String, Object> buildFieldItem(String label, String value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        return item;
    }

    /**
     * 构建分组信息。
     *
     * @param key 分组键
     * @param title 分组标题
     * @param items 明细项
     * @return 分组数据
     */
    private Map<String, Object> buildSection(String key, String title, List<Map<String, Object>> items) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("key", key);
        section.put("title", title);
        section.put("items", items);
        return section;
    }

    /**
     * 判断当前载荷是否成功。
     *
     * @param payload 工具载荷
     * @return 成功时返回 true
     */
    private boolean isSuccessful(Map<String, Object> payload) {
        return !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error")));
    }

    /**
     * 统一字段名格式。
     *
     * @param key 原始字段名
     * @return 归一化字段名
     */
    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 安全写入文本字段。
     *
     * @param target 目标对象
     * @param key 字段名
     * @param value 字段值
     */
    private void putText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    /**
     * 从多个候选值中取首个非空文本。
     *
     * @param values 候选值列表
     * @return 首个非空文本
     */
    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    /**
     * 安全文本化对象。
     *
     * @param value 任意对象
     * @return 文本值
     */
    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    /**
     * 安全转 Map。
     *
     * @param value 任意对象
     * @return Map 数据
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }
}
