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
package com.alibaba.assistant.agent.start.profile.service;

import com.alibaba.assistant.agent.start.profile.dto.ProfileDTO;
import com.alibaba.assistant.agent.start.profile.intent.IntentRecognizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 个人档案 HTTP 服务。
 *
 * <p>该服务负责调用 DataAgent 的 `/api/stream/search` SSE 接口，
 * 并从整条执行流里提取最终查询结果，而不是把中间执行过程返回给前端。</p>
 */
@Service
public class ProfileHttpService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileHttpService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<DataAgentStreamEvent>> EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private static final List<String> TERMINAL_FAILURE_MARKERS = List.of(
            "未检索到相关数据表",
            "流程已终止",
            "未找到匹配档案",
            "未找到相关数据",
            "未查询到相关信息");

    private static final List<String> ZERO_RECORD_MARKERS = List.of(
            "共返回 0 条记录",
            "共返回0条记录");

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WebClient profileWebClient;

    private final String searchPath;

    private final String agentId;

    private final Duration streamIdleTimeout;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ProfileHttpService(
            @Qualifier("profileWebClient") WebClient profileWebClient,
            @Value("${assistant.profile.data-agent.search-path:/api/stream/search}") String searchPath,
            @Value("${assistant.profile.data-agent.agent-id:5}") String agentId,
            @Value("${assistant.profile.data-agent.stream-idle-timeout:PT30S}") String streamIdleTimeout) {
        this.profileWebClient = profileWebClient;
        this.searchPath = searchPath;
        this.agentId = agentId;
        this.streamIdleTimeout = parseStreamIdleTimeout(streamIdleTimeout);
    }

    ProfileHttpService(WebClient profileWebClient, String searchPath, String agentId) {
        this(profileWebClient, searchPath, agentId, DEFAULT_STREAM_IDLE_TIMEOUT.toString());
    }

    /**
     * Query profile archive information.
     *
     * @param name employee name
     * @return normalized profile result
     */
    public Mono<ProfileDTO> queryProfile(String name) {
        return queryProfile(name, IntentRecognizer.IntentType.PROFILE_ARCHIVE);
    }

    /**
     * Query profile-related information for a specific intent.
     *
     * @param name employee name
     * @param intentType query intent
     * @return normalized profile result
     */
    public Mono<ProfileDTO> queryProfile(String name, IntentRecognizer.IntentType intentType) {
        return queryProfile(name, intentType, null);
    }

    /**
     * Query profile-related information for a specific intent.
     *
     * @param name display name
     * @param intentType query intent
     * @param userId optional OA user id
     * @return normalized profile result
     */
    public Mono<ProfileDTO> queryProfile(String name, IntentRecognizer.IntentType intentType, Long userId) {
        IntentRecognizer.IntentType resolvedIntentType = Optional.ofNullable(intentType)
                .orElse(IntentRecognizer.IntentType.PROFILE_ARCHIVE);
        return requestStream(name, resolvedIntentType, userId)
                .timeout(streamIdleTimeout)
                .<DataAgentStreamEvent>handle((event, sink) -> {
                    if (event.error()) {
                        sink.error(new IllegalStateException(resolveErrorMessage(event)));
                        return;
                    }
                    sink.next(event);
                })
                .takeUntil(DataAgentStreamEvent::complete)
                .collectList()
                .map(events -> aggregateEvents(name, resolvedIntentType, events))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(200))
                        .filter(this::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnSuccess(profileDTO -> logger.info(
                        "ProfileHttpService#queryProfile - success, name={}, threadId={}",
                        profileDTO.name(),
                        profileDTO.threadId()))
                .doOnError(error -> logger.warn(
                        "ProfileHttpService#queryProfile - failed, name={}, error={}",
                        name,
                        error.getMessage()));
    }

    private Flux<DataAgentStreamEvent> requestStream(
            String name,
            IntentRecognizer.IntentType intentType,
            Long userId) {
        return profileWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(searchPath)
                        .queryParam("agentId", agentId)
                        .queryParam("query", buildProfileQuery(name, intentType, userId))
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "DataAgent HTTP 调用失败，status=" + response.statusCode().value() + ", body=" + body))))
                .bodyToFlux(EVENT_TYPE)
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull);
    }

    private ProfileDTO aggregateEvents(
            String name,
            IntentRecognizer.IntentType intentType,
            List<DataAgentStreamEvent> events) {
        String threadId = events.stream()
                .map(DataAgentStreamEvent::threadId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);

        Optional<ProfileDTO> resultSetProfile = extractResultSetProfile(name, intentType, threadId, events);
        if (resultSetProfile.isPresent()) {
            return resultSetProfile.get();
        }

        String fallbackText = extractTerminalText(events);
        if (!StringUtils.hasText(fallbackText)) {
            throw new IllegalStateException(resolveNoResultMessage(intentType));
        }
        if (intentType == IntentRecognizer.IntentType.PROFILE_ASSET_IN_USE
                && containsZeroRecordMarker(fallbackText)) {
            throw new IllegalStateException(resolveNoResultMessage(intentType));
        }
        if (containsTerminalFailure(fallbackText)) {
            throw new IllegalStateException(resolveFailureMessage(intentType, fallbackText));
        }
        return new ProfileDTO(name, fallbackText, fallbackText, threadId);
    }

    private Optional<ProfileDTO> extractResultSetProfile(
            String name,
            IntentRecognizer.IntentType intentType,
            String threadId,
            List<DataAgentStreamEvent> events) {
        return events.stream()
                .filter(this::isResultSetEvent)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .reduce((previous, current) -> current)
                .flatMap(resultSetText -> parseResultSetProfile(name, intentType, threadId, resultSetText));
    }

    private Optional<ProfileDTO> parseResultSetProfile(
            String name,
            IntentRecognizer.IntentType intentType,
            String threadId,
            String resultSetText) {
        try {
            Map<String, Object> payload = objectMapper.readValue(resultSetText, MAP_TYPE);
            Map<String, Object> resultSet = asMap(payload.get("resultSet"));
            String errorMsg = asText(resultSet.get("errorMsg"));
            if (StringUtils.hasText(errorMsg)) {
                throw new IllegalStateException(errorMsg);
            }

            Object dataObject = resultSet.get("data");
            if (!(dataObject instanceof List<?> rows) || rows.isEmpty()) {
                return Optional.empty();
            }

            List<Map<String, Object>> normalizedRows = normalizeResultRows(rows);
            if (normalizedRows.isEmpty()) {
                return Optional.empty();
            }

            Map<String, Object> firstRow = normalizedRows.get(0);
            String resolvedName = resolveResultName(name, normalizedRows);
            String rawText = objectMapper.writeValueAsString(firstRow);
            String summary = buildResultSetSummary(resolvedName, intentType, normalizedRows);
            return Optional.of(new ProfileDTO(resolvedName, summary, rawText, threadId, normalizedRows));
        }
        catch (IllegalStateException illegalStateException) {
            throw illegalStateException;
        }
        catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> normalizeResultRows(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        for (Object rowObject : rows) {
            if (rowObject instanceof Map<?, ?> rowMap && !rowMap.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> currentRow = new LinkedHashMap<>((Map<String, Object>) rowMap);
                normalizedRows.add(currentRow);
            }
        }
        return normalizedRows;
    }

    private String buildResultSetSummary(
            String displayName,
            IntentRecognizer.IntentType intentType,
            List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() == 1) {
            return buildRowSummary(rows.get(0), intentType);
        }
        return switch (Optional.ofNullable(intentType).orElse(IntentRecognizer.IntentType.PROFILE_ARCHIVE)) {
            case PROFILE_ASSET_IN_USE -> "已查询到%s的%d条在用资产".formatted(displayName, rows.size());
            case PROFILE_SCHEDULE -> "已查询到%s的%d条日程".formatted(displayName, rows.size());
            case PROFILE_GENERAL -> "已查询到%s的%d条个人信息".formatted(displayName, rows.size());
            case PROFILE_ARCHIVE, UNKNOWN -> "已查询到%s的%d条相关记录".formatted(displayName, rows.size());
        };
    }

    private String resolveResultName(String requestedName, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return requestedName;
        }
        String rowName = resolveRowValue(rows.get(0),
                "name",
                "employeeName",
                "employee_name",
                "userName",
                "user_name",
                "assignee",
                "姓名",
                "使用人员",
                "领用人",
                "员工姓名");
        return StringUtils.hasText(rowName) ? rowName : requestedName;
    }

    private String buildRowSummary(Map<String, Object> row, IntentRecognizer.IntentType intentType) {
        if (IntentRecognizer.IntentType.PROFILE_ASSET_IN_USE == intentType) {
            String assetSummary = buildAssetRowSummary(row);
            if (StringUtils.hasText(assetSummary)) {
                return assetSummary;
            }
        }

        List<String> summaryParts = new ArrayList<>();
        addIfHasText(summaryParts, resolveRowValue(row, "name", "employeeName", "employee_name", "姓名", "员工姓名"));
        addIfHasText(summaryParts, resolveRowValue(row, "gender", "sex", "性别", "员工性别"));
        Optional.ofNullable(resolveRowValue(row, "age", "年龄"))
                .filter(StringUtils::hasText)
                .map(age -> age.endsWith("岁") ? age : age + "岁")
                .ifPresent(summaryParts::add);
        addIfHasText(summaryParts, resolveRowValue(row,
                "position",
                "positionName",
                "position_name",
                "job",
                "title",
                "职位",
                "职务",
                "岗位"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "date",
                "day",
                "scheduleDate",
                "schedule_date",
                "startDate",
                "start_date",
                "日期"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "schedule",
                "agenda",
                "event",
                "eventName",
                "event_name",
                "content",
                "日程",
                "行程",
                "事项"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "city",
                "currentAddress",
                "current_address",
                "现居地地址",
                "城市"));
        if (summaryParts.size() > 1) {
            return String.join("，", summaryParts);
        }
        return row.entrySet().stream()
                .filter(entry -> StringUtils.hasText(asText(entry.getValue())))
                .map(entry -> entry.getKey() + "：" + asText(entry.getValue()))
                .collect(Collectors.joining("；"));
    }

    private String buildAssetRowSummary(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        List<String> summaryParts = new ArrayList<>();
        addIfHasText(summaryParts, resolveRowValue(row,
                "assetName", "asset_name", "assetUnitName", "asset_unit_name", "unitName", "unit_name", "资产名称", "单位名称"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "assetCode", "asset_code", "code", "编号", "资产编码", "资产编号"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "assetModel", "asset_model", "model", "规格型号", "资产型号"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "assetStatus", "status", "状态", "资产状态"));
        addIfHasText(summaryParts, resolveRowValue(row,
                "assetSource", "source", "来源", "资产来源"));
        return summaryParts.isEmpty() ? null : String.join("，", summaryParts);
    }

    private String resolveRowValue(Map<String, Object> row, String... aliases) {
        if (row == null || row.isEmpty() || aliases == null || aliases.length == 0) {
            return null;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            for (String alias : aliases) {
                if (normalizeKey(alias).equals(normalizedKey)) {
                    String value = asText(entry.getValue());
                    return isGenderAlias(alias) ? normalizeGenderValue(value) : value;
                }
            }
        }
        return null;
    }

    private boolean isGenderAlias(String alias) {
        String normalizedAlias = normalizeKey(alias);
        return "gender".equals(normalizedAlias)
                || "sex".equals(normalizedAlias)
                || "性别".equals(alias)
                || "员工性别".equals(alias);
    }

    private String normalizeGenderValue(String rawGender) {
        if (!StringUtils.hasText(rawGender)) {
            return rawGender;
        }
        String normalized = rawGender.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "m", "male", "man", "男" -> "男";
            case "0", "2", "f", "female", "woman", "女" -> "女";
            default -> rawGender.trim();
        };
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

    private String extractTerminalText(List<DataAgentStreamEvent> events) {
        String terminalNodeName = events.stream()
                .filter(this::isReadableTextEvent)
                .map(DataAgentStreamEvent::nodeName)
                .filter(StringUtils::hasText)
                .reduce((previous, current) -> current)
                .orElse(null);

        return events.stream()
                .filter(this::isReadableTextEvent)
                .filter(event -> shouldIncludeEventForAggregation(event, terminalNodeName))
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .reduce("", String::concat)
                .trim();
    }

    private boolean isRetryable(Throwable throwable) {
        return !(throwable instanceof IllegalArgumentException || throwable instanceof IllegalStateException);
    }

    private String resolveErrorMessage(DataAgentStreamEvent event) {
        return Optional.ofNullable(event)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .orElse("DataAgent 返回错误事件");
    }

    private boolean isReadableTextEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && "TEXT".equalsIgnoreCase(Optional.ofNullable(event.textType()).orElse(""));
    }

    private boolean isResultSetEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && "RESULT_SET".equalsIgnoreCase(Optional.ofNullable(event.textType()).orElse(""));
    }

    private boolean shouldIncludeEventForAggregation(DataAgentStreamEvent event, String terminalNodeName) {
        if (!StringUtils.hasText(terminalNodeName)) {
            return true;
        }
        return terminalNodeName.equals(event.nodeName());
    }

    private boolean containsTerminalFailure(String rawText) {
        return TERMINAL_FAILURE_MARKERS.stream().anyMatch(rawText::contains);
    }

    private boolean containsZeroRecordMarker(String rawText) {
        return ZERO_RECORD_MARKERS.stream().anyMatch(rawText::contains);
    }

    private String extractFailureMessage(String rawText) {
        return rawText.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> TERMINAL_FAILURE_MARKERS.stream().anyMatch(line::contains))
                .findFirst()
                .orElse(rawText);
    }

    private String resolveNoResultMessage(IntentRecognizer.IntentType intentType) {
        return intentType == IntentRecognizer.IntentType.PROFILE_ASSET_IN_USE
                ? "暂无该用户使用记录"
                : "DataAgent 未返回有效文本结果";
    }

    private String resolveFailureMessage(IntentRecognizer.IntentType intentType, String rawText) {
        if (intentType == IntentRecognizer.IntentType.PROFILE_ASSET_IN_USE) {
            return "暂无该用户使用记录";
        }
        return extractFailureMessage(rawText);
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

    private Duration parseStreamIdleTimeout(String configuredTimeout) {
        if (!StringUtils.hasText(configuredTimeout)) {
            return DEFAULT_STREAM_IDLE_TIMEOUT;
        }
        try {
            Duration parsedTimeout = Duration.parse(configuredTimeout.trim());
            if (parsedTimeout.isZero() || parsedTimeout.isNegative()) {
                return DEFAULT_STREAM_IDLE_TIMEOUT;
            }
            return parsedTimeout;
        }
        catch (DateTimeParseException exception) {
            logger.warn(
                    "ProfileHttpService#parseStreamIdleTimeout - invalid value={}, fallback={}",
                    configuredTimeout,
                    DEFAULT_STREAM_IDLE_TIMEOUT,
                    exception);
            return DEFAULT_STREAM_IDLE_TIMEOUT;
        }
    }

    private String buildProfileQuery(String name, IntentRecognizer.IntentType intentType, Long userId) {
        IntentRecognizer.IntentType resolvedIntentType = Optional.ofNullable(intentType)
                .orElse(IntentRecognizer.IntentType.PROFILE_ARCHIVE);
        return switch (resolvedIntentType) {
            case PROFILE_ASSET_IN_USE -> ("查询用户ID为%s的当前正在使用的资产，"
                    + "返回资产名称、资产编码、资产型号、资产分类、资产品牌、质保到期日、单位、购买价格、购买日期、年折旧率、资产状态、资产来源。")
                    .formatted(String.valueOf(userId));
            case PROFILE_SCHEDULE -> ("查询姓名为%s的个人日程、日历或排期信息，"
                    + "返回该人员日程相关的核心字段和值。").formatted(name);
            case PROFILE_GENERAL -> ("查询姓名为%s的个人信息，"
                    + "返回该人员相关的核心信息字段和值。").formatted(name);
            case PROFILE_ARCHIVE, UNKNOWN -> ("查询姓名为%s的个人档案信息，"
                    + "返回该人员的核心档案字段和值。").formatted(name);
        };
    }

    private record DataAgentStreamEvent(
            String agentId,
            String threadId,
            String nodeName,
            String textType,
            String text,
            boolean error,
            boolean complete) {
    }
}
