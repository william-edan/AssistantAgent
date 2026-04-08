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
package com.alibaba.assistant.agent.start.profile.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.start.profile.tool.ProfileQueryTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protocol bridge for profile query results.
 */
@Component
@Profile("migration")
@Order(125)
public class ProfileQueryProtocolStrategy implements ProtocolStrategy {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String PROFILE_CARD_TEMPLATE_CODE = "PROFILE_CARD";

    private static final String RECORD_TYPE_SINGLE = "single";

    private static final String RECORD_TYPE_LIST = "list";

    private static final String TITLE_SUFFIX_PROFILE = "的个人档案";

    private static final String TITLE_SUFFIX_SCHEDULE = "的个人日程";

    private static final String TITLE_SUFFIX_GENERAL = "的个人信息";

    private static final String TITLE_SUFFIX_ASSET = "的在用资产";

    private static final String TITLE_QUERY_FAILED = "个人档案查询失败";

    private static final String VALUE_QUERY_FAILED = "查询失败";

    private static final String VALUE_PERSON_FALLBACK = "目标人员";

    private static final String TEXT_QUERY_SUCCESS = "已为你查询到%s的个人档案，下面是关键信息。";

    private static final String TEXT_QUERY_SUCCESS_SCHEDULE = "已为你查询到%s的个人日程信息，下面是关键信息。";

    private static final String TEXT_QUERY_SUCCESS_GENERAL = "已为你查询到%s的个人信息，下面是关键信息。";

    private static final String TEXT_QUERY_SUCCESS_ASSET = "已为你查询到%s的在用资产信息，下面是关键信息。";

    private static final String TEXT_QUERY_SUCCESS_SCHEDULE_LIST = "已为你查询到%s的%d条个人日程信息。";

    private static final String TEXT_QUERY_SUCCESS_ASSET_LIST = "已为你查询到%s的%d条在用资产信息。";

    private static final String LABEL_NAME = "姓名";

    private static final String LABEL_GENDER = "性别";

    private static final String LABEL_AGE = "年龄";

    private static final String LABEL_POSITION = "职务";

    private static final String LABEL_CITY = "城市";

    private static final String LABEL_STATUS = "状态";

    private static final String LABEL_REASON = "失败原因";

    private static final String LABEL_BIRTHDAY = "生日";

    private static final String LABEL_JOB_NUMBER = "工号";

    private static final String LABEL_EDUCATION = "学历";

    private static final String LABEL_GRADUATE_SCHOOL = "毕业学校";

    private static final String LABEL_SPECIALITY = "所学专业";

    private static final String LABEL_MOBILE = "手机号";

    private static final String LABEL_EMAIL = "邮箱";

    private static final String LABEL_CURRENT_ADDRESS = "现居地地址";

    private static final String LABEL_HOME_ADDRESS = "家庭地址";

    private static final String LABEL_PROFILE_NAME = "档案名称";

    private static final String LABEL_SCHEDULE_NAME = "日程名称";

    private static final String LABEL_INFO_NAME = "信息名称";

    private static final String LABEL_SCHEDULE_COUNT = "行程总数";

    private static final String LABEL_EARLIEST_START_TIME = "最早开始时间";

    private static final String LABEL_LATEST_END_TIME = "最晚结束时间";

    private static final String LABEL_RECENT_SCHEDULE = "最近一条安排";

    private static final String LABEL_ASSET_NAME = "资产名称";

    private static final String LABEL_ASSET_CODE = "资产编码";

    private static final String LABEL_ASSET_MODEL = "资产型号";

    private static final String LABEL_ASSET_CATEGORY = "资产分类";

    private static final String LABEL_ASSET_BRAND = "资产品牌";

    private static final String LABEL_WARRANTY_DATE = "质保到期日";

    private static final String LABEL_UNIT = "单位";

    private static final String LABEL_PURCHASE_PRICE = "购买价格";

    private static final String LABEL_PURCHASE_DATE = "购买日期";

    private static final String LABEL_DEPRECIATION_RATE = "年折旧率(%)";

    private static final String LABEL_ASSET_STATUS = "资产状态";

    private static final String LABEL_ASSET_SOURCE = "资产来源";

    private static final String LABEL_ASSET_COUNT = "资产总数";

    private static final String SECTION_BASIC = "基础信息";

    private static final String SECTION_EDUCATION = "教育信息";

    private static final String SECTION_CONTACT = "联系信息";

    private static final String SECTION_EXTRA = "其他信息";

    private static final String SECTION_ASSET = "资产信息";

    private static final String SECTION_FAILURE = "失败信息";

    private static final String GENDER_MALE = "男";

    private static final String GENDER_FEMALE = "女";

    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "([\\p{L}\\p{N}_\\-\\s]+?)\\s*[:：]\\s*(.*?)(?=(?:[，,；;\\n\\r]+[\\p{L}\\p{N}_\\-\\s]+\\s*[:：])|$)");

    private final ProtocolPayloadSupport payloadSupport;

    private final ObjectMapper objectMapper;

    public ProfileQueryProtocolStrategy(ProtocolPayloadSupport payloadSupport, ObjectMapper objectMapper) {
        this.payloadSupport = payloadSupport;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return ProfileQueryTool.TOOL_NAME.equals(normalizedToolName);
    }

    @Override
    public List<FrontendEvent> adapt(
            String threadId,
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return List.of(payloadSupport.resultEvent(threadId, normalizePayload(payload)));
    }

    @Override
    public Map<String, Object> projectThreadState(
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return payloadSupport.projectResultState(normalizePayload(payload), state);
    }

    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("artifactCode", ProfileQueryTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("toolCode", ProfileQueryTool.TOOL_NAME);

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

    private Map<String, Object> buildResult(Map<String, Object> payload) {
        if (!isSuccessful(payload)) {
            return buildFailureResult(payload);
        }

        Map<String, Object> data = asMap(payload.get("data"));
        List<Map<String, Object>> records = extractRecordList(data);
        Map<String, Object> profile = normalizeProfile(extractProfileValues(payload));
        String displayName = resolveDisplayName(payload, data, profile);
        if (shouldBuildAssetListResult(payload, records)) {
            return buildAssetListResult(payload, data, displayName, records);
        }
        if (isAssetIntent(payload)) {
            return buildAssetSingleResult(payload, data, displayName, records, profile);
        }
        if (shouldBuildScheduleListResult(payload, records)) {
            return buildScheduleListResult(payload, data, displayName, records);
        }
        String titleSuffix = resolveTitleSuffix(payload);
        String summary = buildResultText(displayName, payload);
        Set<String> consumedKeys = new LinkedHashSet<>();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", PROFILE_CARD_TEMPLATE_CODE);
        result.put("title", displayName + titleSuffix);
        result.put("summary", summary);
        result.put("text", summary);
        result.put("recordType", RECORD_TYPE_SINGLE);
        result.put("finalOutputs", buildFinalOutputs(profile, displayName, payload));
        result.put("highlights", buildHighlights(profile, displayName, consumedKeys));
        result.put("sections", buildSections(profile, displayName, consumedKeys));
        result.put("profile", profile);
        putText(result, "threadId", asText(data.get("threadId")));
        return result;
    }

    private boolean isAssetIntent(Map<String, Object> payload) {
        return "PROFILE_ASSET_IN_USE".equals(resolveIntent(payload));
    }

    private boolean shouldBuildAssetListResult(Map<String, Object> payload, List<Map<String, Object>> records) {
        return isAssetIntent(payload)
                && records != null
                && records.size() > 1;
    }

    private boolean shouldBuildScheduleListResult(Map<String, Object> payload, List<Map<String, Object>> records) {
        return "PROFILE_SCHEDULE".equals(resolveIntent(payload))
                && records != null
                && records.size() > 1;
    }

    private Map<String, Object> buildScheduleListResult(
            Map<String, Object> payload,
            Map<String, Object> data,
            String displayName,
            List<Map<String, Object>> records) {
        List<Map<String, Object>> displayRecords = buildDisplayRecords(records);
        String summary = TEXT_QUERY_SUCCESS_SCHEDULE_LIST.formatted(displayName, displayRecords.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", PROFILE_CARD_TEMPLATE_CODE);
        result.put("title", displayName + TITLE_SUFFIX_SCHEDULE);
        result.put("summary", summary);
        result.put("text", summary);
        result.put("recordType", RECORD_TYPE_LIST);
        result.put("finalOutputs", buildScheduleListFinalOutputs(displayName, displayRecords));
        result.put("highlights", buildScheduleListHighlights(displayName, displayRecords));
        result.put("sections", buildScheduleListSections(displayRecords));
        result.put("records", displayRecords);
        result.put("profile", buildScheduleListProfile(displayName, displayRecords));
        putText(result, "threadId", asText(data.get("threadId")));
        return result;
    }

    private Map<String, Object> buildAssetSingleResult(
            Map<String, Object> payload,
            Map<String, Object> data,
            String displayName,
            List<Map<String, Object>> records,
            Map<String, Object> profile) {
        Map<String, Object> assetRecord = records != null && !records.isEmpty()
                ? new LinkedHashMap<>(records.get(0))
                : new LinkedHashMap<>(profile);
        Map<String, Object> assetProfile = buildAssetProfile(displayName, assetRecord);
        Map<String, Object> finalOutputs = buildAssetSingleFinalOutputs(displayName, assetProfile);
        String summary = TEXT_QUERY_SUCCESS_ASSET.formatted(displayName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", PROFILE_CARD_TEMPLATE_CODE);
        result.put("title", displayName + TITLE_SUFFIX_ASSET);
        result.put("summary", summary);
        result.put("text", summary);
        result.put("recordType", RECORD_TYPE_SINGLE);
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", buildAssetHighlights(assetProfile));
        result.put("sections", buildAssetSections(assetProfile));
        result.put("profile", assetProfile);
        putText(result, "threadId", asText(data.get("threadId")));
        return result;
    }

    private Map<String, Object> buildAssetListResult(
            Map<String, Object> payload,
            Map<String, Object> data,
            String displayName,
            List<Map<String, Object>> records) {
        List<Map<String, Object>> displayRecords = buildAssetDisplayRecords(records);
        String summary = TEXT_QUERY_SUCCESS_ASSET_LIST.formatted(displayName, displayRecords.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", PROFILE_CARD_TEMPLATE_CODE);
        result.put("title", displayName + TITLE_SUFFIX_ASSET);
        result.put("summary", summary);
        result.put("text", summary);
        result.put("recordType", RECORD_TYPE_LIST);
        result.put("finalOutputs", buildAssetListFinalOutputs(displayName, displayRecords));
        result.put("highlights", buildAssetListHighlights(displayName, displayRecords));
        result.put("sections", buildAssetListSections(displayRecords));
        result.put("records", displayRecords);
        result.put("profile", buildAssetListFinalOutputs(displayName, displayRecords));
        putText(result, "threadId", asText(data.get("threadId")));
        return result;
    }

    private Map<String, Object> buildAssetProfile(String displayName, Map<String, Object> assetRecord) {
        Map<String, Object> assetProfile = new LinkedHashMap<>();
        putText(assetProfile, LABEL_NAME, displayName);
        assetProfile.putAll(buildAssetFinalOutputs(assetRecord));
        return assetProfile;
    }

    private Map<String, Object> buildAssetSingleFinalOutputs(
            String displayName,
            Map<String, Object> assetProfile) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        putText(finalOutputs, LABEL_INFO_NAME, displayName + TITLE_SUFFIX_ASSET);
        putAssetField(finalOutputs, assetProfile, LABEL_NAME);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_NAME);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_CODE);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_STATUS);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_MODEL);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_CATEGORY);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_BRAND);
        putAssetField(finalOutputs, assetProfile, LABEL_WARRANTY_DATE);
        putAssetField(finalOutputs, assetProfile, LABEL_UNIT);
        putAssetField(finalOutputs, assetProfile, LABEL_PURCHASE_PRICE);
        putAssetField(finalOutputs, assetProfile, LABEL_PURCHASE_DATE);
        putAssetField(finalOutputs, assetProfile, LABEL_DEPRECIATION_RATE);
        putAssetField(finalOutputs, assetProfile, LABEL_ASSET_SOURCE);
        return finalOutputs;
    }

    private Map<String, Object> buildAssetFinalOutputs(Map<String, Object> assetRecord) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        putResolvedAssetField(finalOutputs, LABEL_ASSET_NAME, assetRecord,
                "assetName", "asset_name", "assetUnitName", "asset_unit_name", "unitName", "unit_name", "资产名称", "单位名称");
        putResolvedAssetField(finalOutputs, LABEL_ASSET_CODE, assetRecord,
                "assetCode", "asset_code", "code", "编号", "资产编码", "资产编号");
        putResolvedAssetField(finalOutputs, LABEL_ASSET_MODEL, assetRecord,
                "assetModel", "asset_model", "model", "规格型号", "资产型号");
        putResolvedAssetField(finalOutputs, LABEL_ASSET_CATEGORY, assetRecord,
                "assetCategory", "asset_category", "category", "资产分类");
        putResolvedAssetField(finalOutputs, LABEL_ASSET_BRAND, assetRecord,
                "assetBrand", "asset_brand", "brand", "资产品牌");
        putResolvedAssetField(finalOutputs, LABEL_WARRANTY_DATE, assetRecord,
                "warrantyDate", "warranty_date", "warrantyExpireDate", "warranty_expire_date", "质保到期日期", "质保到期日");
        putResolvedAssetField(finalOutputs, LABEL_UNIT, assetRecord,
                "unit", "单位");
        putResolvedAssetField(finalOutputs, LABEL_PURCHASE_PRICE, assetRecord,
                "purchasePrice", "purchase_price", "buyPrice", "buy_price", "price", "价格", "购买价格");
        putResolvedAssetField(finalOutputs, LABEL_PURCHASE_DATE, assetRecord,
                "purchaseDate", "purchase_date", "buyDate", "buy_date", "purchaseInDate", "purchase_in_date", "购进日期", "购买日期");
        putResolvedAssetField(finalOutputs, LABEL_DEPRECIATION_RATE, assetRecord,
                "depreciationRate", "depreciation_rate", "annualDepreciationRate", "annual_depreciation_rate", "年折旧率(%)", "年折旧率");
        putResolvedAssetField(finalOutputs, LABEL_ASSET_STATUS, assetRecord,
                "assetStatus", "asset_status", "status", "状态", "资产状态");
        putResolvedAssetField(finalOutputs, LABEL_ASSET_SOURCE, assetRecord,
                "assetSource", "asset_source", "source", "来源", "资产来源");
        return finalOutputs;
    }

    private void putResolvedAssetField(
            Map<String, Object> target,
            String label,
            Map<String, Object> assetRecord,
            String... aliases) {
        String value = resolveProfileValue(assetRecord, aliases);
        if (StringUtils.hasText(value)) {
            target.put(label, value);
        }
    }

    private void putAssetField(Map<String, Object> target, Map<String, Object> assetProfile, String label) {
        putText(target, label, asText(assetProfile.get(label)));
    }

    private List<Map<String, Object>> buildAssetHighlights(Map<String, Object> finalOutputs) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        addResultItem(highlights, LABEL_NAME, finalOutputs.get(LABEL_NAME));
        addResultItem(highlights, LABEL_ASSET_NAME, finalOutputs.get(LABEL_ASSET_NAME));
        addResultItem(highlights, LABEL_ASSET_CODE, finalOutputs.get(LABEL_ASSET_CODE));
        addResultItem(highlights, LABEL_ASSET_STATUS, finalOutputs.get(LABEL_ASSET_STATUS));
        return highlights;
    }

    private List<Map<String, Object>> buildAssetSections(Map<String, Object> assetProfile) {
        List<Map<String, Object>> sections = new ArrayList<>();

        List<Map<String, Object>> basicItems = new ArrayList<>();
        addAssetItem(basicItems, assetProfile, LABEL_NAME);
        addAssetItem(basicItems, assetProfile, LABEL_ASSET_NAME);
        addAssetItem(basicItems, assetProfile, LABEL_ASSET_CODE);
        addAssetItem(basicItems, assetProfile, LABEL_ASSET_STATUS);
        addSectionIfPresent(sections, "basic", SECTION_BASIC, basicItems);

        List<Map<String, Object>> assetItems = new ArrayList<>();
        addAssetItem(assetItems, assetProfile, LABEL_ASSET_MODEL);
        addAssetItem(assetItems, assetProfile, LABEL_ASSET_CATEGORY);
        addAssetItem(assetItems, assetProfile, LABEL_ASSET_BRAND);
        addAssetItem(assetItems, assetProfile, LABEL_WARRANTY_DATE);
        addAssetItem(assetItems, assetProfile, LABEL_UNIT);
        addAssetItem(assetItems, assetProfile, LABEL_PURCHASE_PRICE);
        addAssetItem(assetItems, assetProfile, LABEL_PURCHASE_DATE);
        addAssetItem(assetItems, assetProfile, LABEL_DEPRECIATION_RATE);
        addAssetItem(assetItems, assetProfile, LABEL_ASSET_SOURCE);
        addSectionIfPresent(sections, "asset", SECTION_ASSET, assetItems);
        return sections;
    }

    private void addAssetItem(List<Map<String, Object>> items, Map<String, Object> assetProfile, String label) {
        addResultItem(items, label, assetProfile.get(label));
    }

    private List<Map<String, Object>> buildAssetItems(Map<String, Object> finalOutputs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Object> entry : finalOutputs.entrySet()) {
            addResultItem(items, entry.getKey(), entry.getValue());
        }
        return items;
    }

    private void addResultItem(List<Map<String, Object>> items, String label, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(label) && StringUtils.hasText(text)) {
            items.add(buildFieldItem(label, text));
        }
    }

    private List<Map<String, Object>> buildAssetDisplayRecords(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> displayRecords = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, Object> displayRecord = buildAssetFinalOutputs(record);
            if (!displayRecord.isEmpty()) {
                displayRecords.add(displayRecord);
            }
        }
        return displayRecords;
    }

    private Map<String, Object> buildAssetListFinalOutputs(
            String displayName,
            List<Map<String, Object>> displayRecords) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        putText(finalOutputs, LABEL_INFO_NAME, displayName + TITLE_SUFFIX_ASSET);
        putText(finalOutputs, LABEL_NAME, displayName);
        putText(finalOutputs, LABEL_ASSET_COUNT, String.valueOf(displayRecords.size()));
        if (!displayRecords.isEmpty()) {
            Map<String, Object> firstRecord = displayRecords.get(0);
            putText(finalOutputs, LABEL_ASSET_NAME, asText(firstRecord.get(LABEL_ASSET_NAME)));
            putText(finalOutputs, LABEL_ASSET_STATUS, asText(firstRecord.get(LABEL_ASSET_STATUS)));
        }
        return finalOutputs;
    }

    private List<Map<String, Object>> buildAssetListHighlights(
            String displayName,
            List<Map<String, Object>> displayRecords) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        addResultItem(highlights, LABEL_NAME, displayName);
        addResultItem(highlights, LABEL_ASSET_COUNT, String.valueOf(displayRecords.size()));
        if (!displayRecords.isEmpty()) {
            Map<String, Object> firstRecord = displayRecords.get(0);
            addResultItem(highlights, LABEL_ASSET_NAME, firstRecord.get(LABEL_ASSET_NAME));
            addResultItem(highlights, LABEL_ASSET_STATUS, firstRecord.get(LABEL_ASSET_STATUS));
        }
        return highlights;
    }

    private List<Map<String, Object>> buildAssetListSections(List<Map<String, Object>> displayRecords) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int index = 0; index < displayRecords.size(); index++) {
            sections.add(buildSection(
                    "asset_" + (index + 1),
                    "第" + (index + 1) + "条资产",
                    buildAssetItems(displayRecords.get(index))));
        }
        return sections;
    }

    private Map<String, Object> buildFailureResult(Map<String, Object> payload) {
        String errorMessage = firstText(payload.get("error"), payload.get("message"), TITLE_QUERY_FAILED);

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("结果说明", errorMessage);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", PROFILE_CARD_TEMPLATE_CODE);
        result.put("title", TITLE_QUERY_FAILED);
        result.put("summary", errorMessage);
        result.put("text", errorMessage);
        result.put("recordType", RECORD_TYPE_SINGLE);
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", List.of(buildFieldItem(LABEL_STATUS, VALUE_QUERY_FAILED)));
        result.put("sections", List.of(buildSection(
                "failure",
                SECTION_FAILURE,
                List.of(buildFieldItem(LABEL_REASON, errorMessage)))));
        result.put("profile", Map.of());
        return result;
    }

    private Map<String, Object> extractProfileValues(Map<String, Object> payload) {
        Map<String, Object> data = asMap(payload.get("data"));

        Map<String, Object> values = parseRawText(asText(data.get("rawText")));
        if (!values.isEmpty()) {
            return values;
        }

        values = parseKeyValueText(firstText(data.get("summary"), payload.get("message")));
        if (!values.isEmpty()) {
            return values;
        }

        Map<String, Object> flattenedData = new LinkedHashMap<>(data);
        flattenedData.remove("summary");
        flattenedData.remove("rawText");
        flattenedData.remove("threadId");
        if (!flattenedData.isEmpty()) {
            return flattenedData;
        }

        String message = firstText(payload.get("message"), payload.get("reply"));
        if (!StringUtils.hasText(message)) {
            return Map.of();
        }
        return Map.of("summary", message);
    }

    private Map<String, Object> normalizeProfile(Map<String, Object> profile) {
        Map<String, Object> normalizedProfile = profile != null ? new LinkedHashMap<>(profile) : new LinkedHashMap<>();
        Optional<String> genderKey = findMatchedKey(normalizedProfile, "gender", "sex", "性别", "员工性别");
        if (genderKey.isEmpty()) {
            return normalizedProfile;
        }

        String rawGender = asText(normalizedProfile.get(genderKey.get()));
        String genderLabel = normalizeGenderValue(rawGender);
        if (!StringUtils.hasText(genderLabel)) {
            return normalizedProfile;
        }
        if (StringUtils.hasText(rawGender) && !genderLabel.equals(rawGender)) {
            normalizedProfile.put("genderCode", rawGender);
        }
        normalizedProfile.put(genderKey.get(), genderLabel);
        normalizedProfile.put("genderLabel", genderLabel);
        return normalizedProfile;
    }

    private List<Map<String, Object>> extractRecordList(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        Object recordsObject = data.get("records");
        if (!(recordsObject instanceof List<?> rawRecords) || rawRecords.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> normalizedRecords = new ArrayList<>();
        for (Object rawRecord : rawRecords) {
            if (rawRecord instanceof Map<?, ?> recordMap && !recordMap.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> currentRecord = new LinkedHashMap<>((Map<String, Object>) recordMap);
                normalizedRecords.add(currentRecord);
            }
        }
        return normalizedRecords;
    }

    private Map<String, Object> buildFinalOutputs(
            Map<String, Object> profile,
            String displayName,
            Map<String, Object> payload) {
        String titleSuffix = resolveTitleSuffix(payload);
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        Set<String> consumedKeys = new LinkedHashSet<>();

        finalOutputs.put(resolvePrimaryOutputLabel(payload), displayName + titleSuffix);
        addFinalOutputItem(finalOutputs, LABEL_NAME, displayName, profile, consumedKeys,
                "name", "employeeName", "employee_name", "姓名", "员工姓名");
        addFinalOutputItem(finalOutputs, LABEL_GENDER,
                resolveProfileValue(profile, "gender", "sex", "性别", "员工性别"),
                profile, consumedKeys, "gender", "sex", "性别", "员工性别");
        addFinalOutputItem(finalOutputs, LABEL_AGE,
                formatAge(resolveProfileValue(profile, "age", "年龄")),
                profile, consumedKeys, "age", "年龄");
        addFinalOutputItem(finalOutputs, LABEL_POSITION,
                resolveProfileValue(profile,
                        "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位"),
                profile, consumedKeys,
                "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位");
        addFinalOutputItem(finalOutputs, LABEL_MOBILE,
                resolveProfileValue(profile, "mobile", "phone", "手机号", "手机号码", "联系电话"),
                profile, consumedKeys, "mobile", "phone", "手机号", "手机号码", "联系电话");
        addFinalOutputItem(finalOutputs, LABEL_BIRTHDAY,
                resolveProfileValue(profile, "birthday", "birthDate", "birth_date", "出生日期", "生日"),
                profile, consumedKeys, "birthday", "birthDate", "birth_date", "出生日期", "生日");
        addFinalOutputItem(finalOutputs, LABEL_SPECIALITY,
                resolveProfileValue(profile, "speciality", "specialty", "major", "所学专业", "专业"),
                profile, consumedKeys, "speciality", "specialty", "major", "所学专业", "专业");
        addFinalOutputItem(finalOutputs, LABEL_HOME_ADDRESS,
                resolveProfileValue(profile, "homeAddress", "home_address", "familyAddress", "family_address", "家庭地址"),
                profile, consumedKeys, "homeAddress", "home_address", "familyAddress", "family_address", "家庭地址");

        appendRemainingProfileOutputs(finalOutputs, profile, consumedKeys);
        return finalOutputs;
    }

    private String resolvePrimaryOutputLabel(Map<String, Object> payload) {
        return switch (resolveIntent(payload)) {
            case "PROFILE_SCHEDULE" -> LABEL_SCHEDULE_NAME;
            case "PROFILE_GENERAL" -> LABEL_INFO_NAME;
            default -> LABEL_PROFILE_NAME;
        };
    }

    private String resolveDisplayName(
            Map<String, Object> payload,
            Map<String, Object> data,
            Map<String, Object> profile) {
        return firstText(
                resolveProfileValue(profile, "name", "employeeName", "employee_name", "姓名", "员工姓名"),
                asText(data.get("name")),
                asText(payload.get("name")),
                VALUE_PERSON_FALLBACK);
    }

    private String resolveProfileSummary(
            Map<String, Object> payload,
            Map<String, Object> data,
            Map<String, Object> profile,
            String displayName) {
        List<String> parts = new ArrayList<>();
        addIfHasText(parts, displayName);
        addIfHasText(parts, resolveProfileValue(profile, "gender", "sex", "性别", "员工性别"));
        Optional.ofNullable(resolveProfileValue(profile, "age", "年龄"))
                .filter(StringUtils::hasText)
                .map(age -> age.endsWith("岁") ? age : age + "岁")
                .ifPresent(parts::add);
        addIfHasText(parts, resolveProfileValue(profile,
                "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位"));
        if (!parts.isEmpty()) {
            return String.join("，", parts);
        }
        return firstText(data.get("summary"), payload.get("message"), displayName + TITLE_SUFFIX_PROFILE);
    }

    private String buildResultText(String displayName, Map<String, Object> payload) {
        return switch (resolveIntent(payload)) {
            case "PROFILE_SCHEDULE" -> TEXT_QUERY_SUCCESS_SCHEDULE.formatted(displayName);
            case "PROFILE_GENERAL" -> TEXT_QUERY_SUCCESS_GENERAL.formatted(displayName);
            default -> TEXT_QUERY_SUCCESS.formatted(displayName);
        };
    }

    private String resolveTitleSuffix(Map<String, Object> payload) {
        return switch (resolveIntent(payload)) {
            case "PROFILE_SCHEDULE" -> TITLE_SUFFIX_SCHEDULE;
            case "PROFILE_GENERAL" -> TITLE_SUFFIX_GENERAL;
            default -> TITLE_SUFFIX_PROFILE;
        };
    }

    private String resolveIntent(Map<String, Object> payload) {
        return Optional.ofNullable(payload)
                .map(currentPayload -> asText(currentPayload.get("intent")))
                .filter(StringUtils::hasText)
                .orElse("PROFILE_ARCHIVE");
    }

    private String formatAge(String age) {
        if (!StringUtils.hasText(age)) {
            return null;
        }
        return age.endsWith("岁") ? age : age + "岁";
    }

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

    private Map<String, Object> parseKeyValueText(String text) {
        if (!StringUtils.hasText(text)) {
            return Map.of();
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        Matcher matcher = KEY_VALUE_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1).trim() : null;
            String value = matcher.group(2) != null ? matcher.group(2).trim() : null;
            if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                parsed.put(key, value);
            }
        }
        return parsed;
    }

    private List<Map<String, Object>> buildHighlights(
            Map<String, Object> profile,
            String displayName,
            Set<String> consumedKeys) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        addResolvedItem(highlights, LABEL_NAME, displayName, profile, consumedKeys,
                "name", "employeeName", "employee_name", "姓名", "员工姓名");
        addResolvedItem(highlights, LABEL_GENDER,
                resolveProfileValue(profile, "gender", "sex", "性别", "员工性别"),
                profile, consumedKeys, "gender", "sex", "性别", "员工性别");
        addResolvedItem(highlights, LABEL_AGE,
                resolveProfileValue(profile, "age", "年龄"),
                profile, consumedKeys, "age", "年龄");
        addResolvedItem(highlights, LABEL_POSITION,
                resolveProfileValue(profile,
                        "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位"),
                profile, consumedKeys,
                "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位");
        if (highlights.size() < 4) {
            addResolvedItem(highlights, LABEL_CITY,
                    resolveProfileValue(profile,
                            "city", "currentAddress", "current_address", "现居地地址", "城市"),
                    profile, consumedKeys,
                    "city", "currentAddress", "current_address", "现居地地址", "城市");
        }
        return highlights;
    }

    private List<Map<String, Object>> buildSections(
            Map<String, Object> profile,
            String displayName,
            Set<String> consumedKeys) {
        List<Map<String, Object>> sections = new ArrayList<>();

        List<Map<String, Object>> basicItems = new ArrayList<>();
        addResolvedItem(basicItems, LABEL_NAME, displayName, profile, consumedKeys,
                "name", "employeeName", "employee_name", "姓名", "员工姓名");
        addResolvedItem(basicItems, LABEL_GENDER,
                resolveProfileValue(profile, "gender", "sex", "性别", "员工性别"),
                profile, consumedKeys, "gender", "sex", "性别", "员工性别");
        addResolvedItem(basicItems, LABEL_AGE,
                resolveProfileValue(profile, "age", "年龄"),
                profile, consumedKeys, "age", "年龄");
        addResolvedItem(basicItems, LABEL_BIRTHDAY,
                resolveProfileValue(profile, "birthday", "birthDate", "birth_date", "出生日期", "生日"),
                profile, consumedKeys, "birthday", "birthDate", "birth_date", "出生日期", "生日");
        addResolvedItem(basicItems, LABEL_POSITION,
                resolveProfileValue(profile,
                        "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位"),
                profile, consumedKeys,
                "position", "positionName", "position_name", "job", "title", "职位", "职务", "岗位");
        addResolvedItem(basicItems, LABEL_JOB_NUMBER,
                resolveProfileValue(profile, "jobNumber", "job_number", "employeeNumber", "employee_no", "工号"),
                profile, consumedKeys, "jobNumber", "job_number", "employeeNumber", "employee_no", "工号");
        addSectionIfPresent(sections, "basic", SECTION_BASIC, basicItems);

        List<Map<String, Object>> educationItems = new ArrayList<>();
        addResolvedItem(educationItems, LABEL_EDUCATION,
                resolveProfileValue(profile, "education", "学历"),
                profile, consumedKeys, "education", "学历");
        addResolvedItem(educationItems, LABEL_GRADUATE_SCHOOL,
                resolveProfileValue(profile, "graduateSchool", "graduate_school", "school", "毕业学校"),
                profile, consumedKeys, "graduateSchool", "graduate_school", "school", "毕业学校");
        addResolvedItem(educationItems, LABEL_SPECIALITY,
                resolveProfileValue(profile, "speciality", "specialty", "major", "所学专业", "专业"),
                profile, consumedKeys, "speciality", "specialty", "major", "所学专业", "专业");
        addSectionIfPresent(sections, "education", SECTION_EDUCATION, educationItems);

        List<Map<String, Object>> contactItems = new ArrayList<>();
        addResolvedItem(contactItems, LABEL_MOBILE,
                resolveProfileValue(profile, "mobile", "phone", "手机号", "手机号码", "联系电话"),
                profile, consumedKeys, "mobile", "phone", "手机号", "手机号码", "联系电话");
        addResolvedItem(contactItems, LABEL_EMAIL,
                resolveProfileValue(profile, "email", "邮箱"),
                profile, consumedKeys, "email", "邮箱");
        addResolvedItem(contactItems, LABEL_CURRENT_ADDRESS,
                resolveProfileValue(profile, "currentAddress", "current_address", "address", "city", "现居地地址", "城市"),
                profile, consumedKeys, "currentAddress", "current_address", "address", "city", "现居地地址", "城市");
        addResolvedItem(contactItems, LABEL_HOME_ADDRESS,
                resolveProfileValue(profile, "homeAddress", "home_address", "familyAddress", "family_address", "家庭地址"),
                profile, consumedKeys, "homeAddress", "home_address", "familyAddress", "family_address", "家庭地址");
        addSectionIfPresent(sections, "contact", SECTION_CONTACT, contactItems);

        List<Map<String, Object>> extraItems = new ArrayList<>();
        for (Map.Entry<String, Object> entry : profile.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            String value = asText(entry.getValue());
            if (!StringUtils.hasText(normalizedKey)
                    || !StringUtils.hasText(value)
                    || consumedKeys.contains(normalizedKey)
                    || "genderlabel".equals(normalizedKey)
                    || "gendercode".equals(normalizedKey)) {
                continue;
            }
            extraItems.add(buildFieldItem(entry.getKey(), value));
        }
        addSectionIfPresent(sections, "extra", SECTION_EXTRA, extraItems);

        return sections;
    }

    private List<Map<String, Object>> buildDisplayRecords(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> displayRecords = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> displayRecord = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : records.get(index).entrySet()) {
                if (StringUtils.hasText(entry.getKey()) && StringUtils.hasText(asText(entry.getValue()))) {
                    displayRecord.put(entry.getKey(), entry.getValue());
                }
            }
            displayRecords.add(displayRecord);
        }
        return displayRecords;
    }

    private Map<String, Object> buildScheduleListFinalOutputs(
            String displayName,
            List<Map<String, Object>> records) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put(LABEL_SCHEDULE_NAME, displayName + TITLE_SUFFIX_SCHEDULE);
        putText(finalOutputs, LABEL_NAME, displayName);
        putText(finalOutputs, LABEL_SCHEDULE_COUNT, String.valueOf(records.size()));
        putText(finalOutputs, LABEL_EARLIEST_START_TIME, minRecordValue(records,
                "startTime", "start_time", "startDate", "start_date", "开始时间", "开始日期"));
        putText(finalOutputs, LABEL_LATEST_END_TIME, maxRecordValue(records,
                "endTime", "end_time", "endDate", "end_date", "结束时间", "结束日期"));
        putText(finalOutputs, LABEL_RECENT_SCHEDULE, firstRecordValue(records,
                "工作安排主题", "schedule", "agenda", "event", "eventName", "event_name", "事项", "日程", "行程"));
        return finalOutputs;
    }

    private List<Map<String, Object>> buildScheduleListHighlights(
            String displayName,
            List<Map<String, Object>> records) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        highlights.add(buildFieldItem(LABEL_NAME, displayName));
        highlights.add(buildFieldItem(LABEL_SCHEDULE_COUNT, String.valueOf(records.size())));

        String earliestStartTime = minRecordValue(records,
                "startTime", "start_time", "startDate", "start_date", "开始时间", "开始日期");
        if (StringUtils.hasText(earliestStartTime)) {
            highlights.add(buildFieldItem(LABEL_EARLIEST_START_TIME, earliestStartTime));
        }

        String recentSchedule = firstRecordValue(records,
                "工作安排主题", "schedule", "agenda", "event", "eventName", "event_name", "事项", "日程", "行程");
        if (StringUtils.hasText(recentSchedule)) {
            highlights.add(buildFieldItem(LABEL_RECENT_SCHEDULE, recentSchedule));
        }
        return highlights;
    }

    private List<Map<String, Object>> buildScheduleListSections(List<Map<String, Object>> records) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Object> entry : records.get(index).entrySet()) {
                String value = asText(entry.getValue());
                if (StringUtils.hasText(entry.getKey()) && StringUtils.hasText(value)) {
                    items.add(buildFieldItem(entry.getKey(), value));
                }
            }
            sections.add(buildSection(
                    "schedule_" + (index + 1),
                    "第" + (index + 1) + "条日程",
                    items));
        }
        return sections;
    }

    private Map<String, Object> buildScheduleListProfile(
            String displayName,
            List<Map<String, Object>> records) {
        return new LinkedHashMap<>(buildScheduleListFinalOutputs(displayName, records));
    }

    private String firstRecordValue(List<Map<String, Object>> records, String... aliases) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        for (Map<String, Object> record : records) {
            String value = resolveProfileValue(record, aliases);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String minRecordValue(List<Map<String, Object>> records, String... aliases) {
        return records == null ? null : records.stream()
                .map(record -> resolveProfileValue(record, aliases))
                .filter(StringUtils::hasText)
                .min(String::compareTo)
                .orElse(null);
    }

    private String maxRecordValue(List<Map<String, Object>> records, String... aliases) {
        return records == null ? null : records.stream()
                .map(record -> resolveProfileValue(record, aliases))
                .filter(StringUtils::hasText)
                .max(String::compareTo)
                .orElse(null);
    }

    private void addResolvedItem(
            List<Map<String, Object>> items,
            String label,
            String value,
            Map<String, Object> profile,
            Set<String> consumedKeys,
            String... aliases) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        items.add(buildFieldItem(label, value));
        findMatchedKey(profile, aliases).ifPresent(key -> consumedKeys.add(normalizeKey(key)));
    }

    private void addFinalOutputItem(
            Map<String, Object> finalOutputs,
            String label,
            String value,
            Map<String, Object> profile,
            Set<String> consumedKeys,
            String... aliases) {
        if (!StringUtils.hasText(label) || !StringUtils.hasText(value)) {
            return;
        }
        finalOutputs.put(label, value);
        findMatchedKey(profile, aliases).ifPresent(key -> consumedKeys.add(normalizeKey(key)));
    }

    private void appendRemainingProfileOutputs(
            Map<String, Object> finalOutputs,
            Map<String, Object> profile,
            Set<String> consumedKeys) {
        if (profile == null || profile.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : profile.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            String value = asText(entry.getValue());
            if (!StringUtils.hasText(entry.getKey())
                    || !StringUtils.hasText(value)
                    || consumedKeys.contains(normalizedKey)
                    || "genderlabel".equals(normalizedKey)
                    || "gendercode".equals(normalizedKey)) {
                continue;
            }
            finalOutputs.putIfAbsent(entry.getKey(), value);
        }
    }

    private void addSectionIfPresent(
            List<Map<String, Object>> sections,
            String key,
            String title,
            List<Map<String, Object>> items) {
        if (!items.isEmpty()) {
            sections.add(buildSection(key, title, items));
        }
    }

    private Map<String, Object> buildFieldItem(String label, String value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        return item;
    }

    private Map<String, Object> buildSection(String key, String title, List<Map<String, Object>> items) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("key", key);
        section.put("title", title);
        section.put("items", items);
        return section;
    }

    private String resolveProfileValue(Map<String, Object> profile, String... aliases) {
        if (profile == null || profile.isEmpty() || aliases == null || aliases.length == 0) {
            return null;
        }
        for (Map.Entry<String, Object> entry : profile.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            for (String alias : aliases) {
                if (normalizeKey(alias).equals(normalizedKey)) {
                    return asText(entry.getValue());
                }
            }
        }
        return null;
    }

    private Optional<String> findMatchedKey(Map<String, Object> profile, String... aliases) {
        if (profile == null || profile.isEmpty() || aliases == null || aliases.length == 0) {
            return Optional.empty();
        }
        for (Map.Entry<String, Object> entry : profile.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            for (String alias : aliases) {
                if (normalizeKey(alias).equals(normalizedKey)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    private String normalizeGenderValue(String rawGender) {
        if (!StringUtils.hasText(rawGender)) {
            return rawGender;
        }
        String normalized = rawGender.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "m", "male", "man", "男" -> GENDER_MALE;
            case "0", "2", "f", "female", "woman", "女" -> GENDER_FEMALE;
            default -> rawGender.trim();
        };
    }

    private boolean isSuccessful(Map<String, Object> payload) {
        return !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error")));
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private void addIfHasText(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

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

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }
}
