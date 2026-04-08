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
package com.alibaba.assistant.agent.start.reward.node;

import com.alibaba.assistant.agent.start.reward.client.RewardsClient;
import com.alibaba.assistant.agent.start.reward.model.RewardAddRequest;
import com.alibaba.assistant.agent.start.reward.model.RewardAddResult;
import com.alibaba.assistant.agent.start.reward.model.RewardCategoryRecord;
import com.alibaba.assistant.agent.start.reward.model.RewardIntentResult;
import com.alibaba.assistant.agent.start.reward.model.RewardNodeResult;
import com.alibaba.assistant.agent.start.reward.model.RewardUserRecord;
import com.alibaba.assistant.agent.start.reward.model.RewardWorkflowContext;
import com.alibaba.assistant.agent.start.reward.service.RewardEmployeeHttpService;
import com.alibaba.assistant.agent.start.reward.util.RewardErrorMessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reward form node.
 */
public class RewardFormNode {

    private static final Logger log = LoggerFactory.getLogger(RewardFormNode.class);

    private static final String USER_LOOKUP_FAILED_MESSAGE =
            "\u672a\u67e5\u8be2\u5230\u5458\u5de5\uff0c\u8bf7\u91cd\u65b0\u9009\u62e9\u5458\u5de5";

    private final RewardEmployeeHttpService rewardEmployeeHttpService;

    private final RewardsClient rewardsClient;

    public RewardFormNode(
            RewardEmployeeHttpService rewardEmployeeHttpService,
            RewardsClient rewardsClient) {
        this.rewardEmployeeHttpService = rewardEmployeeHttpService;
        this.rewardsClient = rewardsClient;
    }

    public Mono<RewardNodeResult> handle(RewardWorkflowContext context) {
        log.info(
                "RewardFormNode#handle - uname={}, confirmed={}",
                Optional.ofNullable(context.intentResult()).map(RewardIntentResult::uname).orElse(null),
                context.confirmed());
        return rewardsClient.listCategories(context.toolContext())
                .flatMap(categories -> prepareValues(context)
                        .flatMap(preparedValues -> buildNodeResult(context, preparedValues, categories)))
                .onErrorResume(error -> Mono.just(errorResult(
                        RewardErrorMessageUtil.resolveMessage(error, "\u5458\u5de5\u5956\u60e9\u5904\u7406\u5931\u8d25"))));
    }

    private Mono<PreparedValues> prepareValues(RewardWorkflowContext context) {
        Map<String, Object> values = buildInitialValues(context);
        String uname = asText(values.get("uname"));
        if (!StringUtils.hasText(uname)) {
            return Mono.just(new PreparedValues(values, null));
        }
        if (StringUtils.hasText(asText(values.get("uid")))) {
            return Mono.just(new PreparedValues(values, null));
        }
        log.info("RewardFormNode#prepareValues - lookup employee via api, uname={}", uname);
        return rewardEmployeeHttpService.findUser(uname, context.toolContext())
                .map(optionalUser -> optionalUser
                        .map(user -> new PreparedValues(applyResolvedUser(values, user), null))
                        .orElseGet(() -> new PreparedValues(values, USER_LOOKUP_FAILED_MESSAGE)));
    }

    private Mono<RewardNodeResult> buildNodeResult(
            RewardWorkflowContext context,
            PreparedValues preparedValues,
            List<RewardCategoryRecord> categories) {
        Map<String, Object> values = normalizeValues(preparedValues.values(), categories);
        List<Map<String, Object>> fields = buildFields(values, categories);
        if (StringUtils.hasText(preparedValues.userMessage())) {
            return Mono.just(formResult(
                    "COLLECTING",
                    "COLLECT",
                    values,
                    fields,
                    List.of(Map.of("name", "uname", "title", "\u5458\u5de5")),
                    false,
                    preparedValues.userMessage()));
        }

        List<Map<String, Object>> missingFields = buildMissingFields(fields, values);
        if (!missingFields.isEmpty()) {
            return Mono.just(formResult(
                    "COLLECTING",
                    "COLLECT",
                    values,
                    fields,
                    missingFields,
                    false,
                    buildCollectMessage(missingFields)));
        }
        if (!context.confirmed()) {
            return Mono.just(formResult(
                    "CONFIRMING",
                    "CONFIRM",
                    values,
                    fields,
                    List.of(),
                    true,
                    "\u8bf7\u786e\u8ba4\u5956\u60e9\u4fe1\u606f\u540e\u63d0\u4ea4\u3002"));
        }

        RewardAddRequest request = new RewardAddRequest(
                asText(values.get("rewards_cate")),
                intValue(values.get("types")),
                asText(values.get("uname")),
                longValue(values.get("uid")),
                new BigDecimal(asText(values.get("cost"))),
                LocalDate.parse(asText(values.get("rewards_time"))),
                asText(values.get("remark")));
        return rewardsClient.addReward(request, context.toolContext())
                .map(result -> successResult(resolveSuccessMessage(result), Map.of(
                        "rewardId", result.rewardId(),
                        "uid", request.uid(),
                        "uname", request.uname())))
                .onErrorResume(error -> Mono.just(errorResult(
                        RewardErrorMessageUtil.resolveMessage(error, "\u5458\u5de5\u5956\u60e9\u5904\u7406\u5931\u8d25"))));
    }

    private Map<String, Object> buildInitialValues(RewardWorkflowContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        RewardIntentResult intentResult = context.intentResult();
        putIfHasText(values, "uname", Optional.ofNullable(intentResult).map(RewardIntentResult::uname).orElse(null));
        putIfNotNull(values, "types", Optional.ofNullable(intentResult)
                .map(RewardIntentResult::types)
                .orElse(1));
        putIfHasText(values, "cost", Optional.ofNullable(intentResult)
                .map(RewardIntentResult::amount)
                .map(amount -> amount.stripTrailingZeros().toPlainString())
                .orElse(null));
        putIfHasText(values, "rewards_time", Optional.ofNullable(intentResult)
                .map(RewardIntentResult::rewardDate)
                .map(LocalDate::toString)
                .orElse(null));
        putIfHasText(values, "remark", Optional.ofNullable(intentResult).map(RewardIntentResult::remark).orElse(null));
        context.slotInputs().forEach((key, value) -> {
            if (value != null) {
                values.put(key, value);
            }
        });
        values.putIfAbsent("types", 1);
        return values;
    }

    private Map<String, Object> applyResolvedUser(Map<String, Object> values, RewardUserRecord user) {
        Map<String, Object> resolvedValues = new LinkedHashMap<>(values);
        resolvedValues.put("uid", user.uid());
        resolvedValues.put("uname", user.uname());
        return resolvedValues;
    }

    private Map<String, Object> normalizeValues(Map<String, Object> values, List<RewardCategoryRecord> categories) {
        Map<String, Object> normalizedValues = new LinkedHashMap<>(values);
        String rewardCategory = asText(normalizedValues.get("rewards_cate"));
        if (!StringUtils.hasText(rewardCategory)) {
            return normalizedValues;
        }
        boolean matchedById = categories.stream()
                .filter(category -> category != null)
                .map(RewardCategoryRecord::id)
                .anyMatch(rewardCategory::equals);
        if (matchedById) {
            return normalizedValues;
        }
        categories.stream()
                .filter(category -> category != null && StringUtils.hasText(category.name()))
                .filter(category -> rewardCategory.equals(category.name())
                        || rewardCategory.contains(category.name())
                        || category.name().contains(rewardCategory))
                .findFirst()
                .map(RewardCategoryRecord::id)
                .ifPresent(categoryId -> normalizedValues.put("rewards_cate", categoryId));
        return normalizedValues;
    }

    private List<Map<String, Object>> buildFields(Map<String, Object> values, List<RewardCategoryRecord> categories) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("uname", "\u5458\u5de5", values.get("uname"), true, "TEXT", null));
        fields.add(field("types", "\u5956\u60e9\u7c7b\u578b", values.get("types"), true, "SELECT", List.of(
                Map.<String, Object>of("label", "\u5956\u52b1", "value", 1),
                Map.<String, Object>of("label", "\u60e9\u7f5a", "value", 2))));
        fields.add(field("rewards_cate", "\u5956\u60e9\u9879\u76ee", values.get("rewards_cate"), true, "SELECT",
                categories.stream()
                        .map(category -> Map.<String, Object>of("label", category.name(), "value", category.id()))
                        .toList()));
        fields.add(field("cost", "\u91d1\u989d", values.get("cost"), true, "TEXT", null));
        fields.add(field("rewards_time", "\u65e5\u671f", values.get("rewards_time"), true, "DATE", null));
        fields.add(field("remark", "\u5907\u6ce8", values.get("remark"), false, "TEXTAREA", null));
        return fields;
    }

    private List<Map<String, Object>> buildMissingFields(List<Map<String, Object>> fields, Map<String, Object> values) {
        return fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.get("required")))
                .filter(field -> !StringUtils.hasText(asText(values.get(field.get("name")))))
                .map(field -> Map.<String, Object>of("name", field.get("name"), "title", field.get("title")))
                .toList();
    }

    private String buildCollectMessage(List<Map<String, Object>> missingFields) {
        List<String> titles = missingFields.stream()
                .map(field -> asText(field.get("title")))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (titles.isEmpty()) {
            return "\u8bf7\u8865\u5168\u5956\u60e9\u4fe1\u606f\u3002";
        }
        return "\u8bf7\u8865\u5168" + String.join("\u3001", titles) + "\u3002";
    }

    private RewardNodeResult formResult(
            String phase,
            String mode,
            Map<String, Object> values,
            List<Map<String, Object>> fields,
            List<Map<String, Object>> missingFields,
            boolean canSubmit,
            String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "FORM");
        payload.put("mode", mode);
        payload.put("toolCode", "reward_workflow");
        payload.put("values", values);
        payload.put("fields", fields);
        payload.put("missingFields", missingFields);
        payload.put("message", message);
        payload.put("canSubmit", canSubmit);
        return new RewardNodeResult(phase, false, payload);
    }

    private RewardNodeResult successResult(String message, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", true);
        payload.put("message", message);
        payload.putAll(extra);
        return new RewardNodeResult("DONE", true, payload);
    }

    private RewardNodeResult errorResult(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", false);
        payload.put("message", message);
        payload.put("error", message);
        return new RewardNodeResult("ERROR", true, payload);
    }

    private String resolveSuccessMessage(RewardAddResult result) {
        if (result != null && StringUtils.hasText(result.message())) {
            return result.message();
        }
        if (result != null && StringUtils.hasText(result.rewardId())) {
            return "\u4fdd\u5b58\u6210\u529f\uff0c\u8bb0\u5f55ID=" + result.rewardId();
        }
        return "\u4fdd\u5b58\u6210\u529f";
    }

    private Map<String, Object> field(
            String name,
            String title,
            Object value,
            boolean required,
            String fieldType,
            List<Map<String, Object>> options) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("title", title);
        field.put("value", value);
        field.put("required", required);
        field.put("type", fieldType);
        if (options != null && !options.isEmpty()) {
            field.put("options", options);
        }
        return field;
    }

    private void putIfHasText(Map<String, Object> values, String key, String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private Integer intValue(Object value) {
        return value == null ? null : Integer.parseInt(String.valueOf(value));
    }

    private Long longValue(Object value) {
        return value == null ? null : Long.parseLong(String.valueOf(value));
    }

    private record PreparedValues(Map<String, Object> values, String userMessage) {

        private PreparedValues {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }
}
