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
package com.alibaba.assistant.agent.execution.step;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.http.RequestBodySerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP 步骤执行器。
 *
 * <p>负责把流程上下文中的变量解析成 HTTP 请求参数，调用外部系统接口，
 * 再根据成功条件和 JsonPath 提取规则生成步骤输出。
 * 这是平台通过接口对接企业系统时最核心的执行器之一。</p>
 */
@Component
public class HttpStepExecutor {

	private static final Logger logger = LoggerFactory.getLogger(HttpStepExecutor.class);

	private final ObjectMapper objectMapper;

	private final RestTemplate restTemplate;

	private final RequestBodySerializer requestSerializer;

	private final SystemAccessProfilePort systemAccessProfilePort;

	private final TokenBroker tokenBroker;

	private final boolean allowMockFallback;

	public HttpStepExecutor(ObjectMapper objectMapper, RequestBodySerializer requestSerializer,
			SystemAccessProfilePort systemAccessProfilePort, TokenBroker tokenBroker) {
		this(objectMapper, requestSerializer, systemAccessProfilePort, tokenBroker, false);
	}

	@Autowired
	public HttpStepExecutor(ObjectMapper objectMapper, RequestBodySerializer requestSerializer,
			SystemAccessProfilePort systemAccessProfilePort, TokenBroker tokenBroker,
			@Value("${assistant.execution.http.allow-mock-fallback:false}") boolean allowMockFallback) {
		this.objectMapper = objectMapper;
		this.requestSerializer = requestSerializer;
		this.systemAccessProfilePort = systemAccessProfilePort;
		this.tokenBroker = tokenBroker;
		this.allowMockFallback = allowMockFallback;
		this.restTemplate = new RestTemplate();
	}

	/**
	 * 执行单个 HTTP 步骤。
	 *
	 * <p>执行顺序是：解析输入参数、补齐鉴权、发起请求、判断成功条件、提取输出。</p>
	 */
	public StepResult execute(StepConfig config, FlowContext context) {
		try {
			Map<String, Object> requestParams = new HashMap<>();
			if (config.getInputMapping() != null) {
				logger.info("HttpStepExecutor#execute - resolving inputMapping={}", config.getInputMapping());
				for (Map.Entry<String, String> entry : config.getInputMapping().entrySet()) {
					String paramName = entry.getKey();
					String expression = entry.getValue();
					Object value = context.resolve(expression);
					requestParams.put(paramName, value);
					logger.debug("HttpStepExecutor#execute - resolved param={}, expr={}, value={}",
							paramName, expression, value);
				}
			}
			logger.info("HttpStepExecutor#execute - method={}, endpoint={}, paramCount={}, params={}",
					config.getMethod(), config.getEndpoint(), requestParams.size(), requestParams);

			String responseBody = executeHttpRequest(config, requestParams, context);
			logger.info("HttpStepExecutor#execute - responseLength={}",
					responseBody != null ? responseBody.length() : 0);

			if (config.getSuccessCondition() != null) {
				boolean success = evaluateCondition(config.getSuccessCondition(), responseBody);
				if (!success) {
					String errorMsg = extractErrorMessage(responseBody);
					return StepResult.failure(errorMsg);
				}
			}

			Map<String, Object> outputs = new HashMap<>();
			if (config.getOutputMapping() != null) {
				for (Map.Entry<String, String> entry : config.getOutputMapping().entrySet()) {
					String outputName = entry.getKey();
					String jsonPath = entry.getValue();
					try {
						Object value = JsonPath.read(responseBody, jsonPath);
						outputs.put(outputName, value);
						logger.info("HttpStepExecutor#execute - extracted output={}, path={}, value={}",
								outputName, jsonPath, value);
					}
					catch (Exception e) {
						logger.warn("HttpStepExecutor#execute - output extraction failed, output={}, path={}, error={}",
								outputName, jsonPath, e.getMessage());
					}
				}
			}

			return StepResult.success(outputs);
		}
		catch (Exception e) {
			logger.error("HttpStepExecutor#execute - execution error", e);
			return StepResult.failure("HTTP request failed: " + e.getMessage());
		}
	}

	private String executeHttpRequest(StepConfig config, Map<String, Object> params, FlowContext context) {
		String currentStepId = context.getCurrentStepId();
		String resolvedBaseUrl = context.getStepBaseUrl(currentStepId);
		Map<String, String> resolvedHeaders = context.getStepRequestHeaders(currentStepId);
		if (StringUtils.hasText(resolvedBaseUrl)) {
			return executeResolvedStepRequest(config, params, resolvedBaseUrl, resolvedHeaders, currentStepId);
		}

		String resolvedSystemCode = firstNonBlank(
				context.getSystemCode(),
				readIdentityParam(params, "system_code", "systemCode"));
		String resolvedAssistantUid = firstNonBlank(
				context.getAssistantUid(),
				readIdentityParam(params, "assistant_uid", "assistantUid"));

		if (StringUtils.hasText(resolvedSystemCode)) {
			context.setSystemCode(resolvedSystemCode);
		}
		if (StringUtils.hasText(resolvedAssistantUid)) {
			context.setAssistantUid(resolvedAssistantUid);
		}

		if (StringUtils.hasText(context.getSystemCode()) && StringUtils.hasText(context.getAssistantUid())) {
			return executeViaSystemProfile(config, params, context);
		}

		if (allowMockFallback) {
			logger.warn("HttpStepExecutor#executeHttpRequest - mock fallback enabled, endpoint={}",
					config.getEndpoint());
			return "{\"code\": 0, \"msg\": \"success\", \"data\": {\"return_id\": 12345}}";
		}

		throw new IllegalStateException("Missing identity context (system_code/assistant_uid), endpoint="
				+ config.getEndpoint());
	}

	private String executeResolvedStepRequest(
			StepConfig config,
			Map<String, Object> params,
			String baseUrl,
			Map<String, String> resolvedHeaders,
			String stepId) {
		try {
			ResolvedRequest resolvedRequest = resolveRequest(config, params, resolvedHeaders);
			String url = buildUrl(baseUrl, resolvedRequest.endpointWithQuery());
			logger.info("HttpStepExecutor#executeResolvedStepRequest - stepId={}, method={}, url={}",
					stepId, config.getMethod(), url);
			HttpHeaders headers = createDefaultHeaders();
			for (Map.Entry<String, String> header : resolvedRequest.headers().entrySet()) {
				if (StringUtils.hasText(header.getKey()) && header.getValue() != null) {
					headers.set(header.getKey(), header.getValue());
				}
			}
			HttpEntity<?> entity = buildRequestEntity(config, resolvedRequest.body(), headers);
			return exchange(config, url, entity);
		}
		catch (Exception e) {
			logger.error("HttpStepExecutor#executeResolvedStepRequest - failed, stepId={}, error={}", stepId,
					e.getMessage(), e);
			throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
		}
	}

	private String readIdentityParam(Map<String, Object> params, String... keys) {
		if (params == null || params.isEmpty() || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			Object direct = params.get(key);
			if (direct != null && StringUtils.hasText(String.valueOf(direct))) {
				return String.valueOf(direct).trim();
			}
			for (Map.Entry<String, Object> entry : params.entrySet()) {
				if (entry.getKey() != null && key.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
					String text = String.valueOf(entry.getValue()).trim();
					if (StringUtils.hasText(text)) {
						return text;
					}
				}
			}
		}
		return null;
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private String executeViaSystemProfile(StepConfig config, Map<String, Object> params, FlowContext context) {
		try {
			String baseUrl = systemAccessProfilePort.getBaseUrl(context.getSystemCode());
			if (baseUrl == null) {
				throw new IllegalArgumentException("System not found: " + context.getSystemCode());
			}

			Optional<TokenLease> leaseOpt = tokenBroker.acquire(context.getAssistantUid(), context.getSystemCode());
			String token = leaseOpt.map(TokenLease::accessToken).orElse(null);
			HttpHeaders headers = createDefaultHeaders();
			if (token != null) {
				String headerName = systemAccessProfilePort.getTokenHeaderName(context.getSystemCode());
				String headerPrefix = systemAccessProfilePort.getTokenHeaderPrefix(context.getSystemCode());
				headers.set(headerName != null ? headerName : "Authorization",
						(headerPrefix != null ? headerPrefix : "Bearer ") + token);
			}
			else {
				logger.warn("HttpStepExecutor#executeViaSystemProfile - no token, systemCode={}, uid={}",
						context.getSystemCode(), context.getAssistantUid());
			}

			ResolvedRequest resolvedRequest = resolveRequest(config, params, toSingleValueMap(headers));
			String url = buildUrl(baseUrl, resolvedRequest.endpointWithQuery());
			logger.info("HttpStepExecutor#executeViaSystemProfile - method={}, url={}", config.getMethod(), url);
			HttpHeaders effectiveHeaders = createDefaultHeaders();
			for (Map.Entry<String, String> header : resolvedRequest.headers().entrySet()) {
				if (StringUtils.hasText(header.getKey()) && header.getValue() != null) {
					effectiveHeaders.set(header.getKey(), header.getValue());
				}
			}
			HttpEntity<?> entity = buildRequestEntity(config, resolvedRequest.body(), effectiveHeaders);
			return exchange(config, url, entity);
		}
		catch (Exception e) {
			logger.error("HttpStepExecutor#executeViaSystemProfile - failed, error={}", e.getMessage(), e);
			throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
		}
	}

	private HttpHeaders createDefaultHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Requested-With", "XMLHttpRequest");
		headers.set("Accept", "*/*");
		return headers;
	}

	private HttpEntity<?> buildRequestEntity(StepConfig config, Object body, HttpHeaders headers)
			throws Exception {
		String contentType = config.getContentType();
		if (MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(contentType)) {
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
			Map<String, Object> formInput = body instanceof Map<?, ?> map ? castObjectMap(map) : Map.of();
			MultiValueMap<String, String> formData = requestSerializer.toFormUrlEncoded(formInput);
			formData.add("_ajax", "1");
			logger.info("HttpStepExecutor#buildRequestEntity - form-urlencoded, paramCount={}, params={}",
					formData.size(), formData);
			return new HttpEntity<>(formData, headers);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (body == null && isReadMethod(config.getMethod())) {
			return new HttpEntity<>(headers);
		}
		Object requestPayload = body != null ? body : Map.of();
		String requestBody = objectMapper.writeValueAsString(requestPayload);
		logger.info("HttpStepExecutor#buildRequestEntity - JSON body, length={}, body={}",
				requestBody.length(), requestBody);
		return new HttpEntity<>(requestBody, headers);
	}

	private String exchange(StepConfig config, String url, HttpEntity<?> entity) {
		ResponseEntity<String> response = restTemplate.exchange(url,
				HttpMethod.valueOf(config.getMethod()), entity, String.class);
		logger.info("HttpStepExecutor#exchange - statusCode={}", response.getStatusCode());
		if (response.getStatusCode().is2xxSuccessful() || response.getStatusCode().is3xxRedirection()) {
			if (response.getStatusCode().is3xxRedirection()) {
				String location = response.getHeaders().getFirst("Location");
				logger.warn("HttpStepExecutor#exchange - redirect, location={}", location);
			}
			return response.getBody() != null ? response.getBody() : "{}";
		}
		throw new RuntimeException("HTTP error: " + response.getStatusCode());
	}

	private String buildUrl(String baseUrl, String endpoint) {
		String normalizedBase = baseUrl != null ? baseUrl.trim() : "";
		String normalizedEndpoint = endpoint != null ? endpoint.trim() : "";
		if (normalizedBase.endsWith("/") && normalizedEndpoint.startsWith("/")) {
			return normalizedBase.substring(0, normalizedBase.length() - 1) + normalizedEndpoint;
		}
		if (!normalizedBase.endsWith("/") && !normalizedEndpoint.startsWith("/")) {
			return normalizedBase + "/" + normalizedEndpoint;
		}
		return normalizedBase + normalizedEndpoint;
	}

	private ResolvedRequest resolveRequest(
			StepConfig config,
			Map<String, Object> params,
			Map<String, String> inheritedHeaders) {
		Map<String, Object> safeParams = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
		Map<String, Object> pathParams = extractGroup(safeParams.remove("path"));
		Map<String, Object> queryParams = extractGroup(safeParams.remove("query"));
		Map<String, Object> headerParams = extractGroup(safeParams.remove("headers"));
		Object body = safeParams.containsKey("body") ? safeParams.remove("body") : null;
		if (body == null && !safeParams.isEmpty()) {
			body = safeParams;
		}
		Map<String, String> headers = new LinkedHashMap<>();
		if (inheritedHeaders != null) {
			headers.putAll(inheritedHeaders);
		}
		for (Map.Entry<String, Object> entry : headerParams.entrySet()) {
			if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null) {
				headers.put(entry.getKey(), String.valueOf(entry.getValue()));
			}
		}
		String endpointWithPath = applyPathParams(config.getEndpoint(), pathParams);
		return new ResolvedRequest(appendQueryParams(endpointWithPath, queryParams), headers, body);
	}

	private String applyPathParams(String endpoint, Map<String, Object> pathParams) {
		String resolvedEndpoint = endpoint != null ? endpoint : "";
		for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
			if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
				continue;
			}
			resolvedEndpoint = resolvedEndpoint.replace("{" + entry.getKey() + "}", urlEncode(entry.getValue()));
		}
		return resolvedEndpoint;
	}

	private String appendQueryParams(String endpoint, Map<String, Object> queryParams) {
		if (queryParams.isEmpty()) {
			return endpoint;
		}
		StringBuilder builder = new StringBuilder(endpoint != null ? endpoint : "");
		boolean hasQuery = builder.indexOf("?") >= 0;
		for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
			if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
				continue;
			}
			List<?> values = entry.getValue() instanceof List<?> list ? list : List.of(entry.getValue());
			for (Object value : values) {
				if (value == null) {
					continue;
				}
				builder.append(hasQuery ? '&' : '?');
				builder.append(urlEncode(entry.getKey()))
						.append('=')
						.append(urlEncode(value));
				hasQuery = true;
			}
		}
		return builder.toString();
	}

	private String urlEncode(Object value) {
		return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8).replace("+", "%20");
	}

	private boolean isReadMethod(String method) {
		return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
	}

	private Map<String, Object> extractGroup(Object value) {
		if (value instanceof Map<?, ?> map) {
			return castObjectMap(map);
		}
		return Map.of();
	}

	private Map<String, Object> castObjectMap(Map<?, ?> source) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : source.entrySet()) {
			if (entry.getKey() != null) {
				normalized.put(String.valueOf(entry.getKey()), entry.getValue());
			}
		}
		return normalized;
	}

	private Map<String, String> toSingleValueMap(HttpHeaders headers) {
		Map<String, String> values = new LinkedHashMap<>();
		if (headers == null || headers.isEmpty()) {
			return values;
		}
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
				values.put(entry.getKey(), entry.getValue().get(0));
			}
		}
		return values;
	}

	private boolean evaluateCondition(String condition, String responseBody) {
		try {
			if (condition.contains("$.code == 0")) {
				Integer code = JsonPath.read(responseBody, "$.code");
				return code != null && code == 0;
			}
			return true;
		}
		catch (Exception e) {
			logger.warn("HttpStepExecutor#evaluateCondition - failed, error={}", e.getMessage());
			return false;
		}
	}

	private String extractErrorMessage(String responseBody) {
		try {
			String msg = JsonPath.read(responseBody, "$.msg");
			return msg != null ? msg : "Unknown error";
		}
		catch (Exception e) {
			return "Response parse failed";
		}
	}

	/**
	 * Port interface for accessing system access profiles.
	 * Implemented by the runtime module's adapter.
	 */
	public interface SystemAccessProfilePort {

		String getBaseUrl(String systemCode);

		String getTokenHeaderName(String systemCode);

		String getTokenHeaderPrefix(String systemCode);

	}

	private record ResolvedRequest(String endpointWithQuery, Map<String, String> headers, Object body) {
	}

}
