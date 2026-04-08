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

    private static final String TITLE_SUFFIX_PROFILE = "\u7684\u4e2a\u4eba\u6863\u6848";

    private static final String TITLE_SUFFIX_SCHEDULE = "\u7684\u4e2a\u4eba\u65e5\u7a0b";

    private static final String TITLE_SUFFIX_GENERAL = "\u7684\u4e2a\u4eba\u4fe1\u606f";

    private static final String TITLE_SUFFIX_ASSET = "\u7684\u5728\u7528\u8d44\u4ea7";

    private static final String TITLE_QUERY_FAILED = "\u4e2a\u4eba\u6863\u6848\u67e5\u8be2\u5931\u8d25";

    private static final String VALUE_QUERY_FAILED = "\u67e5\u8be2\u5931\u8d25";

    private static final String VALUE_PERSON_FALLBACK = "\u76ee\u6807\u4eba\u5458";

    private static final String TEXT_QUERY_SUCCESS = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684\u4e2a\u4eba\u6863\u6848\uff0c\u4e0b\u9762\u662f\u5173\u952e\u4fe1\u606f\u3002";

    private static final String TEXT_QUERY_SUCCESS_SCHEDULE = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684\u4e2a\u4eba\u65e5\u7a0b\u4fe1\u606f\uff0c\u4e0b\u9762\u662f\u5173\u952e\u4fe1\u606f\u3002";

    private static final String TEXT_QUERY_SUCCESS_GENERAL = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684\u4e2a\u4eba\u4fe1\u606f\uff0c\u4e0b\u9762\u662f\u5173\u952e\u4fe1\u606f\u3002";

    private static final String TEXT_QUERY_SUCCESS_ASSET = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684\u5728\u7528\u8d44\u4ea7\u4fe1\u606f\uff0c\u4e0b\u9762\u662f\u5173\u952e\u4fe1\u606f\u3002";

    private static final String TEXT_QUERY_SUCCESS_SCHEDULE_LIST = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684%d\u6761\u4e2a\u4eba\u65e5\u7a0b\u4fe1\u606f\u3002";

    private static final String TEXT_QUERY_SUCCESS_ASSET_LIST = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684%d\u6761\u5728\u7528\u8d44\u4ea7\u4fe1\u606f\u3002";

    private static final String LABEL_NAME = "\u59d3\u540d";

    private static final String LABEL_GENDER = "\u6027\u522b";

    private static final String LABEL_AGE = "\u5e74\u9f84";

    private static final String LABEL_POSITION = "\u804c\u52a1";

    private static final String LABEL_CITY = "\u57ce\u5e02";

    private static final String LABEL_STATUS = "\u72b6\u6001";

    private static final String LABEL_REASON = "\u5931\u8d25\u539f\u56e0";

    private static final String LABEL_BIRTHDAY = "\u751f\u65e5";

    private static final String LABEL_JOB_NUMBER = "\u5de5\u53f7";

    private static final String LABEL_EDUCATION = "\u5b66\u5386";

    private static final String LABEL_GRADUATE_SCHOOL = "\u6bd5\u4e1a\u5b66\u6821";

    private static final String LABEL_SPECIALITY = "\u6240\u5b66\u4e13\u4e1a";

    private static final String LABEL_MOBILE = "\u624b\u673a\u53f7";

    private static final String LABEL_EMAIL = "\u90ae\u7bb1";

    private static final String LABEL_CURRENT_ADDRESS = "\u73b0\u5c45\u5730\u5740";

    private static final String LABEL_HOME_ADDRESS = "\u5bb6\u5ead\u5730\u5740";

    private static final String LABEL_PROFILE_NAME = "\u6863\u6848\u540d\u79f0";

    private static final String LABEL_SCHEDULE_NAME = "\u65e5\u7a0b\u540d\u79f0";

    private static final String LABEL_INFO_NAME = "\u4fe1\u606f\u540d\u79f0";

    private static final String LABEL_SCHEDULE_COUNT = "\u884c\u7a0b\u603b\u6570";

    private static final String LABEL_EARLIEST_START_TIME = "\u6700\u65e9\u5f00\u59cb\u65f6\u95f4";

    private static final String LABEL_LATEST_END_TIME = "\u6700\u665a\u7ed3\u675f\u65f6\u95f4";

    private static final String LABEL_RECENT_SCHEDULE = "\u6700\u8fd1\u4e00\u6761\u5b89\u6392";

    private static final String LABEL_ASSET_NAME = "\u8d44\u4ea7\u540d\u79f0";

    private static final String LABEL_ASSET_CODE = "\u8d44\u4ea7\u7f16\u7801";

    private static final String LABEL_ASSET_MODEL = "\u8d44\u4ea7\u578b\u53f7";

    private static final String LABEL_ASSET_CATEGORY = "\u8d44\u4ea7\u5206\u7c7b";

    private static final String LABEL_ASSET_BRAND = "\u8d44\u4ea7\u54c1\u724c";

    private static final String LABEL_WARRANTY_DATE = "\u8d28\u4fdd\u5230\u671f\u65e5";

    private static final String LABEL_UNIT = "\u5355\u4f4d";

    private static final String LABEL_PURCHASE_PRICE = "\u8d2d\u4e70\u4ef7\u683c";

    private static final String LABEL_PURCHASE_DATE = "\u8d2d\u4e70\u65e5\u671f";

    private static final String LABEL_DEPRECIATION_RATE = "\u5e74\u6298\u65e7\u7387(%)";

    private static final String LABEL_ASSET_STATUS = "\u8d44\u4ea7\u72b6\u6001";

    private static final String LABEL_ASSET_SOURCE = "\u8d44\u4ea7\u6765\u6e90";

    private static final String LABEL_ASSET_COUNT = "\u8d44\u4ea7\u603b\u6570";

    private static final String SECTION_BASIC = "\u57fa\u7840\u4fe1\u606f";

    private static final String SECTION_EDUCATION = "\u6559\u80b2\u4fe1\u606f";

    private static final String SECTION_CONTACT = "\u8054\u7cfb\u4fe1\u606f";

    private static final String SECTION_EXTRA = "\u5176\u4ed6\u4fe1\u606f";

    private static final String SECTION_ASSET = "\u8d44\u4ea7\u4fe1\u606f";

    private static final String SECTION_FAILURE = "\u5931\u8d25\u4fe1\u606f";

    private static final String GENDER_MALE = "\u7537";

    private static final String GENDER_FEMALE = "\u5973";

    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "([\\p{L}\\p{N}_\\-\\s]+?)\\s*[:\\uFF1A]\\s*(.*?)(?=(?:[\\uFF0C,\\uFF1B;\\n\\r]+[\\p{L}\\p{N}_\\-\\s]+\\s*[:\\uFF1A])|$)");

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
        finalOutputs.put("\u7ed3\u679c\u8bf4\u660e", errorMessage);

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
        Optional<String> genderKey = findMatchedKey(normalizedProfile, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b");
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
                "name", "employeeName", "employee_name", "\u59d3\u540d", "\u5458\u5de5\u59d3\u540d");
        addFinalOutputItem(finalOutputs, LABEL_GENDER,
                resolveProfileValue(profile, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b"),
                profile, consumedKeys, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b");
        addFinalOutputItem(finalOutputs, LABEL_AGE,
                formatAge(resolveProfileValue(profile, "age", "\u5e74\u9f84")),
                profile, consumedKeys, "age", "\u5e74\u9f84");
        addFinalOutputItem(finalOutputs, LABEL_POSITION,
                resolveProfileValue(profile,
                        "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d"),
                profile, consumedKeys,
                "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d");
        addFinalOutputItem(finalOutputs, LABEL_MOBILE,
                resolveProfileValue(profile, "mobile", "phone", "\u624b\u673a\u53f7", "\u624b\u673a\u53f7\u7801", "\u8054\u7cfb\u7535\u8bdd"),
                profile, consumedKeys, "mobile", "phone", "\u624b\u673a\u53f7", "\u624b\u673a\u53f7\u7801", "\u8054\u7cfb\u7535\u8bdd");
        addFinalOutputItem(finalOutputs, LABEL_BIRTHDAY,
                resolveProfileValue(profile, "birthday", "birthDate", "birth_date", "\u51fa\u751f\u65e5\u671f", "\u751f\u65e5"),
                profile, consumedKeys, "birthday", "birthDate", "birth_date", "\u51fa\u751f\u65e5\u671f", "\u751f\u65e5");
        addFinalOutputItem(finalOutputs, LABEL_SPECIALITY,
                resolveProfileValue(profile, "speciality", "specialty", "major", "\u6240\u5b66\u4e13\u4e1a", "\u4e13\u4e1a"),
                profile, consumedKeys, "speciality", "specialty", "major", "\u6240\u5b66\u4e13\u4e1a", "\u4e13\u4e1a");
        addFinalOutputItem(finalOutputs, LABEL_HOME_ADDRESS,
                resolveProfileValue(profile, "homeAddress", "home_address", "familyAddress", "family_address", "\u5bb6\u5ead\u5730\u5740"),
                profile, consumedKeys, "homeAddress", "home_address", "familyAddress", "family_address", "\u5bb6\u5ead\u5730\u5740");

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
                resolveProfileValue(profile, "name", "employeeName", "employee_name", "\u59d3\u540d", "\u5458\u5de5\u59d3\u540d"),
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
        addIfHasText(parts, resolveProfileValue(profile, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b"));
        Optional.ofNullable(resolveProfileValue(profile, "age", "\u5e74\u9f84"))
                .filter(StringUtils::hasText)
                .map(age -> age.endsWith("\u5c81") ? age : age + "\u5c81")
                .ifPresent(parts::add);
        addIfHasText(parts, resolveProfileValue(profile,
                "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d"));
        if (!parts.isEmpty()) {
            return String.join("\uff0c", parts);
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
        return age.endsWith("\u5c81") ? age : age + "\u5c81";
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
                "name", "employeeName", "employee_name", "\u59d3\u540d", "\u5458\u5de5\u59d3\u540d");
        addResolvedItem(highlights, LABEL_GENDER,
                resolveProfileValue(profile, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b"),
                profile, consumedKeys, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b");
        addResolvedItem(highlights, LABEL_AGE,
                resolveProfileValue(profile, "age", "\u5e74\u9f84"),
                profile, consumedKeys, "age", "\u5e74\u9f84");
        addResolvedItem(highlights, LABEL_POSITION,
                resolveProfileValue(profile,
                        "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d"),
                profile, consumedKeys,
                "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d");
        if (highlights.size() < 4) {
            addResolvedItem(highlights, LABEL_CITY,
                    resolveProfileValue(profile,
                            "city", "currentAddress", "current_address", "\u73b0\u5c45\u5730\u5740", "\u57ce\u5e02"),
                    profile, consumedKeys,
                    "city", "currentAddress", "current_address", "\u73b0\u5c45\u5730\u5740", "\u57ce\u5e02");
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
                "name", "employeeName", "employee_name", "\u59d3\u540d", "\u5458\u5de5\u59d3\u540d");
        addResolvedItem(basicItems, LABEL_GENDER,
                resolveProfileValue(profile, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b"),
                profile, consumedKeys, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b");
        addResolvedItem(basicItems, LABEL_AGE,
                resolveProfileValue(profile, "age", "\u5e74\u9f84"),
                profile, consumedKeys, "age", "\u5e74\u9f84");
        addResolvedItem(basicItems, LABEL_BIRTHDAY,
                resolveProfileValue(profile, "birthday", "birthDate", "birth_date", "\u51fa\u751f\u65e5\u671f", "\u751f\u65e5"),
                profile, consumedKeys, "birthday", "birthDate", "birth_date", "\u51fa\u751f\u65e5\u671f", "\u751f\u65e5");
        addResolvedItem(basicItems, LABEL_POSITION,
                resolveProfileValue(profile,
                        "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d"),
                profile, consumedKeys,
                "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d");
        addResolvedItem(basicItems, LABEL_JOB_NUMBER,
                resolveProfileValue(profile, "jobNumber", "job_number", "employeeNumber", "employee_no", "\u5de5\u53f7"),
                profile, consumedKeys, "jobNumber", "job_number", "employeeNumber", "employee_no", "\u5de5\u53f7");
        addSectionIfPresent(sections, "basic", SECTION_BASIC, basicItems);

        List<Map<String, Object>> educationItems = new ArrayList<>();
        addResolvedItem(educationItems, LABEL_EDUCATION,
                resolveProfileValue(profile, "education", "\u5b66\u5386"),
                profile, consumedKeys, "education", "\u5b66\u5386");
        addResolvedItem(educationItems, LABEL_GRADUATE_SCHOOL,
                resolveProfileValue(profile, "graduateSchool", "graduate_school", "school", "\u6bd5\u4e1a\u5b66\u6821"),
                profile, consumedKeys, "graduateSchool", "graduate_school", "school", "\u6bd5\u4e1a\u5b66\u6821");
        addResolvedItem(educationItems, LABEL_SPECIALITY,
                resolveProfileValue(profile, "speciality", "specialty", "major", "\u6240\u5b66\u4e13\u4e1a", "\u4e13\u4e1a"),
                profile, consumedKeys, "speciality", "specialty", "major", "\u6240\u5b66\u4e13\u4e1a", "\u4e13\u4e1a");
        addSectionIfPresent(sections, "education", SECTION_EDUCATION, educationItems);

        List<Map<String, Object>> contactItems = new ArrayList<>();
        addResolvedItem(contactItems, LABEL_MOBILE,
                resolveProfileValue(profile, "mobile", "phone", "\u624b\u673a\u53f7", "\u624b\u673a\u53f7\u7801", "\u8054\u7cfb\u7535\u8bdd"),
                profile, consumedKeys, "mobile", "phone", "\u624b\u673a\u53f7", "\u624b\u673a\u53f7\u7801", "\u8054\u7cfb\u7535\u8bdd");
        addResolvedItem(contactItems, LABEL_EMAIL,
                resolveProfileValue(profile, "email", "\u90ae\u7bb1"),
                profile, consumedKeys, "email", "\u90ae\u7bb1");
        addResolvedItem(contactItems, LABEL_CURRENT_ADDRESS,
                resolveProfileValue(profile, "currentAddress", "current_address", "address", "city", "\u73b0\u5c45\u5730\u5740", "\u57ce\u5e02"),
                profile, consumedKeys, "currentAddress", "current_address", "address", "city", "\u73b0\u5c45\u5730\u5740", "\u57ce\u5e02");
        addResolvedItem(contactItems, LABEL_HOME_ADDRESS,
                resolveProfileValue(profile, "homeAddress", "home_address", "familyAddress", "family_address", "\u5bb6\u5ead\u5730\u5740"),
                profile, consumedKeys, "homeAddress", "home_address", "familyAddress", "family_address", "\u5bb6\u5ead\u5730\u5740");
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
                "startTime", "start_time", "startDate", "start_date", "\u5f00\u59cb\u65f6\u95f4", "\u5f00\u59cb\u65e5\u671f"));
        putText(finalOutputs, LABEL_LATEST_END_TIME, maxRecordValue(records,
                "endTime", "end_time", "endDate", "end_date", "\u7ed3\u675f\u65f6\u95f4", "\u7ed3\u675f\u65e5\u671f"));
        putText(finalOutputs, LABEL_RECENT_SCHEDULE, firstRecordValue(records,
                "\u5de5\u4f5c\u5b89\u6392\u4e3b\u9898", "schedule", "agenda", "event", "eventName", "event_name", "\u4e8b\u9879", "\u65e5\u7a0b", "\u884c\u7a0b"));
        return finalOutputs;
    }

    private List<Map<String, Object>> buildScheduleListHighlights(
            String displayName,
            List<Map<String, Object>> records) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        highlights.add(buildFieldItem(LABEL_NAME, displayName));
        highlights.add(buildFieldItem(LABEL_SCHEDULE_COUNT, String.valueOf(records.size())));

        String earliestStartTime = minRecordValue(records,
                "startTime", "start_time", "startDate", "start_date", "\u5f00\u59cb\u65f6\u95f4", "\u5f00\u59cb\u65e5\u671f");
        if (StringUtils.hasText(earliestStartTime)) {
            highlights.add(buildFieldItem(LABEL_EARLIEST_START_TIME, earliestStartTime));
        }

        String recentSchedule = firstRecordValue(records,
                "\u5de5\u4f5c\u5b89\u6392\u4e3b\u9898", "schedule", "agenda", "event", "eventName", "event_name", "\u4e8b\u9879", "\u65e5\u7a0b", "\u884c\u7a0b");
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
                    "\u7b2c" + (index + 1) + "\u6761\u65e5\u7a0b",
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
            case "1", "m", "male", "man", "\u7537" -> GENDER_MALE;
            case "0", "2", "f", "female", "woman", "\u5973" -> GENDER_FEMALE;
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
