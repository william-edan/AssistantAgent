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
package com.alibaba.assistant.agent.start.reward.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * 奖惩流程使用的 DataAgent 查询服务。
 *
 * <p>DataAgent 查询接口默认以 SSE 流形式返回，因此这里不再叠加较短的底层读超时，
 * 统一使用上层的 stream idle timeout 作为兜底。</p>
 */
@Service
@Profile("migration")
public class DefaultDataAgentService implements DataAgentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataAgentService.class);

    private static final ParameterizedTypeReference<ServerSentEvent<DataAgentStreamEvent>> EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient dataAgentWebClient;

    private final String searchPath;

    private final String agentId;

    private final int maxRetries;

    private final Duration streamIdleTimeout;

    @Autowired
    public DefaultDataAgentService(
            @Qualifier("rewardDataAgentWebClient") WebClient dataAgentWebClient,
            @Value("${assistant.reward.data-agent.search-path:/api/stream/search}") String searchPath,
            @Value("${assistant.reward.data-agent.agent-id:5}") String agentId,
            @Value("${assistant.reward.data-agent.max-retries:2}") int maxRetries,
            @Value("${assistant.reward.data-agent.stream-idle-timeout:PT30S}") String streamIdleTimeout) {
        this.dataAgentWebClient = dataAgentWebClient;
        this.searchPath = searchPath;
        this.agentId = agentId;
        this.maxRetries = maxRetries;
        this.streamIdleTimeout = parseStreamIdleTimeout(streamIdleTimeout);
    }

    DefaultDataAgentService(WebClient dataAgentWebClient, String searchPath, String agentId) {
        this(dataAgentWebClient, searchPath, agentId, 2, DEFAULT_STREAM_IDLE_TIMEOUT.toString());
    }

    @Override
    public Mono<String> query(String prompt) {
        log.info("DefaultDataAgentService#query - prompt={}", prompt);
        return requestStream(prompt)
                .timeout(streamIdleTimeout)
                .<DataAgentStreamEvent>handle((event, sink) -> {
                    if (event.error()) {
                        sink.error(new IllegalStateException(resolveEventErrorMessage(event)));
                        return;
                    }
                    sink.next(event);
                })
                .takeUntil(DataAgentStreamEvent::complete)
                .collectList()
                .map(this::extractResult)
                .flatMap(result -> StringUtils.hasText(result)
                        ? Mono.just(result)
                        : Mono.error(new IllegalStateException("DataAgent 返回为空")))
                .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(200))
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> log.info(
                                "DefaultDataAgentService#query - retry={}, reason={}",
                                signal.totalRetries() + 1,
                                buildReadableErrorMessage(signal.failure(), prompt)))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorMap(error -> adaptError(error, prompt))
                .doOnSuccess(result -> log.info("DefaultDataAgentService#query - success, length={}", result.length()))
                .doOnError(error -> log.warn(
                        "DefaultDataAgentService#query - failed, error={}",
                        buildReadableErrorMessage(error, prompt),
                        error));
    }

    private Flux<DataAgentStreamEvent> requestStream(String prompt) {
        return dataAgentWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(searchPath)
                        .queryParam("agentId", agentId)
                        .queryParam("query", prompt)
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)
                .exchangeToFlux(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany(body -> Flux.error(new IllegalStateException(
                                        "DataAgent HTTP 调用失败，status="
                                                + response.statusCode().value()
                                                + ", body=" + body)));
                    }
                    MediaType contentType = response.headers().contentType().orElse(MediaType.TEXT_PLAIN);
                    if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                        return response.bodyToFlux(EVENT_TYPE)
                                .map(ServerSentEvent::data)
                                .filter(Objects::nonNull);
                    }
                    return response.bodyToMono(String.class)
                            .map(body -> body == null ? "" : body.trim())
                            .filter(StringUtils::hasText)
                            .map(body -> new DataAgentStreamEvent(agentId, null, "reward_data_agent", "TEXT", body, false, true))
                            .flux();
                });
    }

    private String extractResult(List<DataAgentStreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        String resultSetText = events.stream()
                .filter(this::isResultSetEvent)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .reduce((previous, current) -> current)
                .orElse(null);
        if (StringUtils.hasText(resultSetText)) {
            return resultSetText.trim();
        }
        String aggregatedText = aggregateReadableText(events);
        if (StringUtils.hasText(aggregatedText)) {
            return aggregatedText;
        }
        return events.stream()
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .reduce((previous, current) -> current)
                .map(String::trim)
                .orElse(null);
    }

    private String aggregateReadableText(List<DataAgentStreamEvent> events) {
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

    private boolean shouldIncludeEventForAggregation(DataAgentStreamEvent event, String terminalNodeName) {
        if (!StringUtils.hasText(terminalNodeName) || !StringUtils.hasText(event.nodeName())) {
            return true;
        }
        return terminalNodeName.equals(event.nodeName());
    }

    private boolean isResultSetEvent(DataAgentStreamEvent event) {
        return event != null
                && "RESULT_SET".equalsIgnoreCase(event.textType())
                && StringUtils.hasText(event.text());
    }

    private boolean isReadableTextEvent(DataAgentStreamEvent event) {
        return event != null
                && StringUtils.hasText(event.text())
                && !isResultSetEvent(event);
    }

    private String resolveEventErrorMessage(DataAgentStreamEvent event) {
        return Optional.ofNullable(event)
                .map(DataAgentStreamEvent::text)
                .filter(StringUtils::hasText)
                .orElse("DataAgent 返回错误事件");
    }

    private Duration parseStreamIdleTimeout(String configuredTimeout) {
        if (!StringUtils.hasText(configuredTimeout)) {
            return DEFAULT_STREAM_IDLE_TIMEOUT;
        }
        try {
            Duration duration = Duration.parse(configuredTimeout.trim());
            if (duration.isNegative() || duration.isZero()) {
                return DEFAULT_STREAM_IDLE_TIMEOUT;
            }
            return duration;
        }
        catch (DateTimeParseException exception) {
            log.warn(
                    "DefaultDataAgentService#parseStreamIdleTimeout - invalid value={}, fallback={}",
                    configuredTimeout,
                    DEFAULT_STREAM_IDLE_TIMEOUT,
                    exception);
            return DEFAULT_STREAM_IDLE_TIMEOUT;
        }
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientRequestException || throwable instanceof TimeoutException) {
            return true;
        }
        if (!(throwable instanceof IllegalStateException illegalStateException)) {
            return false;
        }
        String message = illegalStateException.getMessage();
        return StringUtils.hasText(message)
                && (message.contains("status=5")
                || message.contains("ReadTimeoutException")
                || message.contains("返回为空")
                || message.contains("DataAgent"));
    }

    private Throwable adaptError(Throwable throwable, String prompt) {
        if (throwable instanceof IllegalStateException illegalStateException
                && StringUtils.hasText(illegalStateException.getMessage())) {
            return illegalStateException;
        }
        return new IllegalStateException(buildReadableErrorMessage(throwable, prompt), throwable);
    }

    private String buildReadableErrorMessage(Throwable throwable, String prompt) {
        if (throwable == null) {
            return "DataAgent 查询失败";
        }
        if (throwable instanceof TimeoutException) {
            return "DataAgent 查询超时，prompt=%s，streamIdleTimeout=%s".formatted(prompt, streamIdleTimeout);
        }
        if (throwable instanceof WebClientRequestException webClientRequestException) {
            Throwable cause = webClientRequestException.getCause();
            if (cause instanceof TimeoutException
                    || (cause != null && cause.getClass().getSimpleName().contains("ReadTimeoutException"))) {
                return "DataAgent 查询超时，prompt=%s，streamIdleTimeout=%s".formatted(prompt, streamIdleTimeout);
            }
            String detail = Optional.ofNullable(webClientRequestException.getCause())
                    .map(Throwable::getMessage)
                    .filter(StringUtils::hasText)
                    .orElseGet(() -> Optional.ofNullable(webClientRequestException.getMessage()).orElse("连接异常"));
            return "DataAgent 连接失败，prompt=%s，detail=%s".formatted(prompt, detail);
        }
        if (StringUtils.hasText(throwable.getMessage())) {
            return throwable.getMessage();
        }
        return "DataAgent 查询失败，prompt=%s，exception=%s".formatted(prompt, throwable.getClass().getSimpleName());
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
