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

    /**
     * 限制流空闲超时，不限制整条流总时长。
     */
    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * DataAgent 终止性失败关键词。
     */
    private static final List<String> TERMINAL_FAILURE_MARKERS = List.of(
            "未检索到相关数据表",
            "流程已终止",
            "未找到匹配档案",
            "未找到相关数据",
            "未查询到相关信息");

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WebClient profileWebClient;

    private final String searchPath;

    private final String agentId;

    private final Duration streamIdleTimeout;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ProfileHttpService(
            WebClient profileWebClient,
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
     * 查询指定姓名的个人档案。
     *
     * @param name 待查询姓名
     * @return 归一化后的档案结果
     */
    public Mono<ProfileDTO> queryProfile(String name) {
        return queryProfile(name, IntentRecognizer.IntentType.PROFILE_ARCHIVE);
    }

    public Mono<ProfileDTO> queryProfile(String name, IntentRecognizer.IntentType intentType) {
        IntentRecognizer.IntentType resolvedIntentType = Optional.ofNullable(intentType)
                .orElse(IntentRecognizer.IntentType.PROFILE_ARCHIVE);
        return requestStream(name, resolvedIntentType)
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
                        profileDTO.name(), profileDTO.threadId()))
                .doOnError(error -> logger.warn(
                        "ProfileHttpService#queryProfile - failed, name={}, error={}",
                        name, error.getMessage()));
    }

    /**
     * 发起 SSE 请求。
     *
     * @param name 查询姓名
     * @return SSE 事件流
     */
    private Flux<DataAgentStreamEvent> requestStream(String name, IntentRecognizer.IntentType intentType) {
        return profileWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(searchPath)
                        .queryParam("agentId", agentId)
                        .queryParam("query", buildProfileQuery(name, intentType))
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

    /**
     * 生成发送给 DataAgent 的自然语言查询。
     *
     * <p>这里只保留单一查询意图，避免把“未找到时如何处理”的逻辑也塞给 NL2SQL，
     * 从而诱导 DataAgent 生成多语句 SQL。</p>
     *
     * @param name 姓名
     * @return 查询语句
     */
    private String buildProfileQuery(String name) {
        return ("查询姓名为%s的个人档案信息，"
                + "返回该人员的核心档案字段和值。").formatted(name);
    }

    /**
     * 聚合 SSE 事件为单个 DTO。
     *
     * @param name 姓名
     * @param events SSE 事件列表
     * @return 聚合后的 DTO
     */
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
            throw new IllegalStateException("DataAgent 未返回有效文本结果");
        }
        if (containsTerminalFailure(fallbackText)) {
            throw new IllegalStateException(extractFailureMessage(fallbackText));
        }
        return new ProfileDTO(name, fallbackText, fallbackText, threadId);
    }

    /**
     * 优先提取最终 RESULT_SET 结果。
     *
     * @param name 查询姓名
     * @param threadId 线程 ID
     * @param events SSE 事件列表
     * @return 命中最终结果时返回 DTO
     */
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

    /**
     * 解析 RESULT_SET 事件文本。
     *
     * @param name 查询姓名
     * @param threadId 线程 ID
     * @param resultSetText RESULT_SET 文本
     * @return 解析后的 DTO
     */
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

    /**
     * 归一化 RESULT_SET 中的多条记录，保留原始字段顺序。
     *
     * @param rows DataAgent 返回的结果行
     * @return 归一化后的记录列表
     */
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

    /**
     * 构建结果集摘要，多条记录时优先输出列表汇总文案。
     *
     * @param displayName 展示姓名
     * @param intentType 意图类型
     * @param rows 结果记录
     * @return 摘要文本
     */
    private String buildResultSetSummary(
            String displayName,
            IntentRecognizer.IntentType intentType,
            List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() == 1) {
            return buildRowSummary(rows.get(0));
        }
        return switch (Optional.ofNullable(intentType).orElse(IntentRecognizer.IntentType.PROFILE_ARCHIVE)) {
            case PROFILE_SCHEDULE -> "已查询到%s的%d条日程".formatted(displayName, rows.size());
            case PROFILE_GENERAL -> "已查询到%s的%d条个人信息".formatted(displayName, rows.size());
            case PROFILE_ARCHIVE, UNKNOWN -> "已查询到%s的%d条相关记录".formatted(displayName, rows.size());
        };
    }

    /**
     * 解析结果展示姓名，优先使用结果集中的姓名字段，未命中时回退到查询姓名。
     *
     * @param requestedName 查询姓名
     * @param rows 结果记录
     * @return 展示姓名
     */
    private String resolveResultName(String requestedName, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return requestedName;
        }
        String rowName = resolveRowValue(rows.get(0), "name", "employeeName", "employee_name", "姓名", "员工姓名");
        return StringUtils.hasText(rowName) ? rowName : requestedName;
    }

    /**
     * 构建最终返回给前端的档案摘要。
     *
     * @param row 首条结果记录
     * @return 人类可读的摘要文本
     */
    private String buildRowSummary(Map<String, Object> row) {
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
        addIfHasText(summaryParts, resolveRowValue(row, "city", "currentAddress", "current_address", "现居地址", "城市"));
        if (summaryParts.size() > 1) {
            return String.join("，", summaryParts);
        }
        return row.entrySet().stream()
                .filter(entry -> StringUtils.hasText(asText(entry.getValue())))
                .map(entry -> entry.getKey() + "：" + asText(entry.getValue()))
                .collect(Collectors.joining("，"));
    }

    /**
     * 根据别名解析结果行中的目标字段值。
     *
     * @param row 查询结果行
     * @param aliases 目标字段别名
     * @return 命中的字段值
     */
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

    /**
     * 判断当前别名是否表示性别字段。
     *
     * @param alias 字段别名
     * @return 是否为性别字段
     */
    private boolean isGenderAlias(String alias) {
        String normalizedAlias = normalizeKey(alias);
        return "gender".equals(normalizedAlias)
                || "sex".equals(normalizedAlias)
                || "\u6027\u522b".equals(alias)
                || "\u5458\u5de5\u6027\u522b".equals(alias);
    }

    /**
     * 将性别编码转换成前端友好的展示值。
     *
     * @param rawGender 原始性别值
     * @return 归一化后的展示值
     */
    private String normalizeGenderValue(String rawGender) {
        if (!StringUtils.hasText(rawGender)) {
            return rawGender;
        }
        String normalized = rawGender.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "m", "male", "man", "\u7537" -> "\u7537";
            case "0", "2", "f", "female", "woman", "\u5973" -> "\u5973";
            default -> rawGender.trim();
        };
    }

    /**
     * 统一字段名格式，便于兼容不同命名风格的结果列。
     *
     * @param key 原始字段名
     * @return 归一化后的字段名
     */
    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 仅在文本非空时追加到摘要片段中。
     *
     * @param parts 摘要片段集合
     * @param value 文本值
     */
    private void addIfHasText(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    /**
     * 从普通文本事件中提取末尾节点文本。
     *
     * @param events SSE 事件列表
     * @return 聚合后的末尾文本
     */
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

    /**
     * 判断当前异常是否允许重试。
     *
     * @param throwable 异常
     * @return 是否允许重试
     */
    private boolean isRetryable(Throwable throwable) {
        return !(throwable instanceof IllegalArgumentException || throwable instanceof IllegalStateException);
    }

    /**
     * 解析错误事件的提示文本。
     *
     * @param event SSE 错误事件
     * @return 错误信息
     */
    private String resolveErrorMessage(DataAgentStreamEvent event) {
        return Optional.ofNullable(event)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .orElse("DataAgent 返回错误事件");
    }

    /**
     * 判断事件是否为可聚合的普通文本事件。
     *
     * @param event SSE 事件
     * @return 是普通文本时返回 true
     */
    private boolean isReadableTextEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && "TEXT".equalsIgnoreCase(Optional.ofNullable(event.textType()).orElse(""));
    }

    /**
     * 判断事件是否为最终结果集事件。
     *
     * @param event SSE 事件
     * @return 是结果集事件时返回 true
     */
    private boolean isResultSetEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && "RESULT_SET".equalsIgnoreCase(Optional.ofNullable(event.textType()).orElse(""));
    }

    /**
     * 判断事件是否应该参与末尾文本聚合。
     *
     * @param event 当前事件
     * @param terminalNodeName 最终文本节点名
     * @return 应参与聚合时返回 true
     */
    private boolean shouldIncludeEventForAggregation(DataAgentStreamEvent event, String terminalNodeName) {
        if (!StringUtils.hasText(terminalNodeName)) {
            return true;
        }
        return terminalNodeName.equals(event.nodeName());
    }

    /**
     * 判断文本中是否包含终止性失败信号。
     *
     * @param rawText 文本
     * @return 命中失败信号时返回 true
     */
    private boolean containsTerminalFailure(String rawText) {
        return TERMINAL_FAILURE_MARKERS.stream().anyMatch(rawText::contains);
    }

    /**
     * 从失败文本中提取更适合返回给前端的错误摘要。
     *
     * @param rawText 失败文本
     * @return 错误摘要
     */
    private String extractFailureMessage(String rawText) {
        return rawText.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> TERMINAL_FAILURE_MARKERS.stream().anyMatch(line::contains))
                .findFirst()
                .orElse(rawText);
    }

    /**
     * 安全转换为文本。
     *
     * @param value 任意值
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
     * 安全转换为 Map。
     *
     * @param value 任意值
     * @return Map 结果
     */
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

    /**
     * DataAgent SSE 事件载荷。
     *
     * @param agentId 智能体编号
     * @param threadId 会话线程标识
     * @param nodeName 节点名称
     * @param textType 文本类型
     * @param text 文本内容
     * @param error 是否错误
     * @param complete 是否完成
     */
    private String buildProfileQuery(String name, IntentRecognizer.IntentType intentType) {
        IntentRecognizer.IntentType resolvedIntentType = Optional.ofNullable(intentType)
                .orElse(IntentRecognizer.IntentType.PROFILE_ARCHIVE);
        return switch (resolvedIntentType) {
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
