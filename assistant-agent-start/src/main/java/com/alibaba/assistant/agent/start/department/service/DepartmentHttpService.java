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
package com.alibaba.assistant.agent.start.department.service;

import com.alibaba.assistant.agent.start.department.dto.DepartmentDTO;
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
 * 部门编制与变动 HTTP 服务。
 *
 * <p>负责调用 DataAgent 的流式搜索接口，并从 SSE 执行流中提取最终结果集，
 * 保证 AssistantAgent 返回给前端的是结构化部门统计结果，而不是中间执行日志。</p>
 */
@Service
public class DepartmentHttpService {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentHttpService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<DataAgentStreamEvent>> EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WebClient dataAgentWebClient;

    private final String searchPath;

    private final String agentId;

    private final Duration streamIdleTimeout;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public DepartmentHttpService(
            @Qualifier("profileWebClient") WebClient dataAgentWebClient,
            @Value("${assistant.department.data-agent.search-path:${assistant.profile.data-agent.search-path:/api/stream/search}}")
            String searchPath,
            @Value("${assistant.department.data-agent.agent-id:${assistant.profile.data-agent.agent-id:5}}")
            String agentId,
            @Value("${assistant.department.data-agent.stream-idle-timeout:PT30S}")
            String streamIdleTimeout) {
        this.dataAgentWebClient = dataAgentWebClient;
        this.searchPath = searchPath;
        this.agentId = agentId;
        this.streamIdleTimeout = parseStreamIdleTimeout(streamIdleTimeout);
    }

    /**
     * 查询部门编制与变动信息。
     *
     * @param userInput 用户原始输入
     * @return 聚合后的部门统计结果
     */
    public Mono<DepartmentDTO> queryDepartmentStaffing(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalArgumentException("部门编制与变动查询不能为空"));

        return requestStream(normalizedInput)
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
                .map(events -> aggregateEvents(normalizedInput, events))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(200))
                        .filter(this::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnSuccess(dto -> logger.info(
                        "DepartmentHttpService#queryDepartmentStaffing - success, query={}, threadId={}",
                        normalizedInput,
                        dto.threadId()))
                .doOnError(error -> logger.warn(
                        "DepartmentHttpService#queryDepartmentStaffing - failed, query={}, error={}",
                        normalizedInput,
                        error.getMessage()));
    }

    /**
     * 发起 SSE 查询请求。
     *
     * @param userInput 用户原始输入
     * @return SSE 事件流
     */
    private Flux<DataAgentStreamEvent> requestStream(String userInput) {
        return dataAgentWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(searchPath)
                        .queryParam("agentId", agentId)
                        .queryParam("query", buildDepartmentQuery(userInput))
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
     * 构造发送给 DataAgent 的查询语句。
     *
     * @param userInput 用户原始输入
     * @return DataAgent 查询语句
     */
    private String buildDepartmentQuery(String userInput) {
        return "%s，按部门维度返回结果集，包含部门名称以及编制、在岗、入职、离职、变动等核心字段和值。"
                .formatted(userInput);
    }

    /**
     * 聚合 SSE 事件为统一 DTO。
     *
     * @param userInput 用户原始输入
     * @param events SSE 事件列表
     * @return 聚合结果
     */
    private DepartmentDTO aggregateEvents(String userInput, List<DataAgentStreamEvent> events) {
        String threadId = events.stream()
                .map(DataAgentStreamEvent::threadId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);

        Optional<DepartmentDTO> resultSetDepartment = extractResultSetDepartment(userInput, threadId, events);
        if (resultSetDepartment.isPresent()) {
            return resultSetDepartment.get();
        }

        String fallbackText = extractTerminalText(events);
        if (!StringUtils.hasText(fallbackText)) {
            throw new IllegalStateException("DataAgent 未返回有效的部门统计结果");
        }
        return new DepartmentDTO(buildQueryTitle(userInput), fallbackText, fallbackText, threadId);
    }

    /**
     * 优先提取最终 RESULT_SET 结果。
     *
     * @param userInput 用户原始输入
     * @param threadId 线程 ID
     * @param events SSE 事件
     * @return 命中结果集时返回 DTO
     */
    private Optional<DepartmentDTO> extractResultSetDepartment(
            String userInput,
            String threadId,
            List<DataAgentStreamEvent> events) {
        return events.stream()
                .filter(this::isResultSetEvent)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .reduce((previous, current) -> current)
                .flatMap(resultSetText -> parseResultSetDepartment(userInput, threadId, resultSetText));
    }

    /**
     * 解析 RESULT_SET 文本。
     *
     * @param userInput 用户原始输入
     * @param threadId 线程 ID
     * @param resultSetText 结果集文本
     * @return 解析结果
     */
    private Optional<DepartmentDTO> parseResultSetDepartment(
            String userInput,
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
            String rawText = objectMapper.writeValueAsString(firstRow);
            String summary = buildResultSetSummary(normalizedRows);
            return Optional.of(new DepartmentDTO(
                    buildQueryTitle(userInput),
                    summary,
                    rawText,
                    threadId,
                    normalizedRows));
        }
        catch (IllegalStateException illegalStateException) {
            throw illegalStateException;
        }
        catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * 归一化多条结果记录。
     *
     * @param rows 原始记录列表
     * @return 归一化结果
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
     * 构建结果集摘要。
     *
     * @param rows 结果记录
     * @return 摘要文本
     */
    private String buildResultSetSummary(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            return "已查询到%d个部门的编制和变动情况。".formatted(rows.size());
        }
        return buildRowSummary(rows.get(0));
    }

    /**
     * 构建单条部门记录摘要。
     *
     * @param row 单条记录
     * @return 摘要文本
     */
    private String buildRowSummary(Map<String, Object> row) {
        List<String> summaryParts = new ArrayList<>();
        addIfHasText(summaryParts, resolveRowValue(row, "部门名称", "部门", "departmentName", "deptName"));
        addIfHasText(summaryParts, prefixValue("编制", resolveRowValue(row, "编制人数", "编制", "核定编制")));
        addIfHasText(summaryParts, prefixValue("在岗", resolveRowValue(row, "在岗人数", "在岗", "实际人数")));
        addIfHasText(summaryParts, prefixValue("入职", resolveRowValue(row, "入职人数", "入职", "新增人数", "新增")));
        addIfHasText(summaryParts, prefixValue("离职", resolveRowValue(row, "离职人数", "离职", "减少人数", "减少")));
        addIfHasText(summaryParts, prefixValue("变动", resolveRowValue(row, "变动人数", "变动", "净变动")));
        if (!summaryParts.isEmpty()) {
            return String.join("，", summaryParts);
        }
        return row.entrySet().stream()
                .filter(entry -> StringUtils.hasText(asText(entry.getValue())))
                .map(entry -> entry.getKey() + "：" + asText(entry.getValue()))
                .collect(Collectors.joining("，"));
    }

    /**
     * 构建查询标题。
     *
     * @param userInput 用户原始输入
     * @return 查询标题
     */
    private String buildQueryTitle(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .orElse("部门编制与变动查询");
        return StringUtils.hasText(normalizedInput) ? normalizedInput : "部门编制与变动查询";
    }

    /**
     * 从文本事件中提取末尾可读文本。
     *
     * @param events SSE 事件
     * @return 聚合文本
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
     * 解析错误事件文本。
     *
     * @param event 错误事件
     * @return 错误信息
     */
    private String resolveErrorMessage(DataAgentStreamEvent event) {
        return Optional.ofNullable(event)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .orElse("DataAgent 返回错误事件");
    }

    /**
     * 判断当前异常是否允许重试。
     *
     * @param throwable 异常对象
     * @return 允许重试时返回 true
     */
    private boolean isRetryable(Throwable throwable) {
        return !(throwable instanceof IllegalArgumentException || throwable instanceof IllegalStateException);
    }

    /**
     * 判断事件是否为普通可读文本。
     *
     * @param event SSE 事件
     * @return 普通文本事件时返回 true
     */
    private boolean isReadableTextEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && "TEXT".equalsIgnoreCase(Optional.ofNullable(event.textType()).orElse(""));
    }

    /**
     * 判断事件是否为结果集事件。
     *
     * @param event SSE 事件
     * @return 结果集事件时返回 true
     */
    private boolean isResultSetEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && "RESULT_SET".equalsIgnoreCase(Optional.ofNullable(event.textType()).orElse(""));
    }

    /**
     * 判断当前文本事件是否应参与聚合。
     *
     * @param event 当前事件
     * @param terminalNodeName 末尾节点名
     * @return 应参与聚合时返回 true
     */
    private boolean shouldIncludeEventForAggregation(DataAgentStreamEvent event, String terminalNodeName) {
        if (!StringUtils.hasText(terminalNodeName)) {
            return true;
        }
        return terminalNodeName.equals(event.nodeName());
    }

    /**
     * 根据别名从结果行中取值。
     *
     * @param row 结果行
     * @param aliases 字段别名
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
                    return asText(entry.getValue());
                }
            }
        }
        return null;
    }

    /**
     * 统一字段名格式，兼容不同命名风格。
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
     * 为值补充前缀标签。
     *
     * @param label 标签
     * @param value 值
     * @return 带标签的展示值
     */
    private String prefixValue(String label, String value) {
        if (!StringUtils.hasText(label) || !StringUtils.hasText(value)) {
            return null;
        }
        return label + value;
    }

    /**
     * 仅在文本非空时添加到摘要片段中。
     *
     * @param parts 摘要片段
     * @param value 文本值
     */
    private void addIfHasText(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    /**
     * 安全转文本。
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
     * @return Map 结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    /**
     * 解析流空闲超时时间。
     *
     * @param configuredTimeout 配置值
     * @return 超时时间
     */
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
                    "DepartmentHttpService#parseStreamIdleTimeout - invalid value={}, fallback={}",
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
     * @param threadId 线程 ID
     * @param nodeName 节点名称
     * @param textType 文本类型
     * @param text 文本内容
     * @param error 是否错误
     * @param complete 是否完成
     */
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
