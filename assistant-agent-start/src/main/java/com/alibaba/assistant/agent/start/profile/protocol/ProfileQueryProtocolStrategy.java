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

    private static final String TITLE_SUFFIX_PROFILE = "\u7684\u4e2a\u4eba\u6863\u6848";

    private static final String TITLE_QUERY_FAILED = "\u4e2a\u4eba\u6863\u6848\u67e5\u8be2\u5931\u8d25";

    private static final String VALUE_QUERY_FAILED = "\u67e5\u8be2\u5931\u8d25";

    private static final String VALUE_PERSON_FALLBACK = "\u76ee\u6807\u4eba\u5458";

    private static final String TEXT_QUERY_SUCCESS = "\u5df2\u4e3a\u4f60\u67e5\u8be2\u5230%s\u7684\u4e2a\u4eba\u6863\u6848\uff0c\u4e0b\u9762\u662f\u5173\u952e\u4fe1\u606f\u3002";

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

    private static final String SECTION_BASIC = "\u57fa\u7840\u4fe1\u606f";

    private static final String SECTION_EDUCATION = "\u6559\u80b2\u4fe1\u606f";

    private static final String SECTION_CONTACT = "\u8054\u7cfb\u4fe1\u606f";

    private static final String SECTION_EXTRA = "\u5176\u4ed6\u4fe1\u606f";

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
        Map<String, Object> profile = normalizeProfile(extractProfileValues(payload));
        String displayName = resolveDisplayName(payload, data, profile);
        String summary = buildResultText(displayName);
        Set<String> consumedKeys = new LinkedHashSet<>();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", PROFILE_CARD_TEMPLATE_CODE);
        result.put("title", displayName + TITLE_SUFFIX_PROFILE);
        result.put("summary", summary);
        result.put("text", summary);
        result.put("recordType", RECORD_TYPE_SINGLE);
        result.put("finalOutputs", buildFinalOutputs(profile, displayName, summary));
        result.put("highlights", buildHighlights(profile, displayName, consumedKeys));
        result.put("sections", buildSections(profile, displayName, consumedKeys));
        result.put("profile", profile);
        putText(result, "threadId", asText(data.get("threadId")));
        return result;
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

    private Map<String, Object> buildFinalOutputs(
            Map<String, Object> profile,
            String displayName,
            String summary) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put(LABEL_PROFILE_NAME, displayName + TITLE_SUFFIX_PROFILE);
        putText(finalOutputs, LABEL_NAME, displayName);
        putText(finalOutputs, LABEL_GENDER, resolveProfileValue(profile, "gender", "sex", "\u6027\u522b", "\u5458\u5de5\u6027\u522b"));
        putText(finalOutputs, LABEL_AGE, formatAge(resolveProfileValue(profile, "age", "\u5e74\u9f84")));
        putText(finalOutputs, LABEL_POSITION, resolveProfileValue(profile,
                "position", "positionName", "position_name", "job", "title", "\u804c\u4f4d", "\u804c\u52a1", "\u5c97\u4f4d"));
        putText(finalOutputs, LABEL_MOBILE, resolveProfileValue(profile,
                "mobile", "phone", "\u624b\u673a\u53f7", "\u624b\u673a\u53f7\u7801", "\u8054\u7cfb\u7535\u8bdd"));
        putText(finalOutputs, LABEL_BIRTHDAY, resolveProfileValue(profile,
                "birthday", "birthDate", "birth_date", "\u51fa\u751f\u65e5\u671f", "\u751f\u65e5"));
        putText(finalOutputs, LABEL_SPECIALITY, resolveProfileValue(profile,
                "speciality", "specialty", "major", "\u6240\u5b66\u4e13\u4e1a", "\u4e13\u4e1a"));
        putText(finalOutputs, LABEL_HOME_ADDRESS, resolveProfileValue(profile,
                "homeAddress", "home_address", "familyAddress", "family_address", "\u5bb6\u5ead\u5730\u5740"));
        return finalOutputs;
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

    private String buildResultText(String displayName) {
        return TEXT_QUERY_SUCCESS.formatted(displayName);
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
