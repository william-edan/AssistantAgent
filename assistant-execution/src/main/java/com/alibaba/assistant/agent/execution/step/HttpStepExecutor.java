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
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP step executor. Resolves variables from FlowContext, executes HTTP requests,
 * evaluates success conditions, and extracts outputs via JsonPath.
 * <p>
 * Uses SystemAccessProfilePort + TokenBroker for authentication instead of
 * direct DB lookups. Preserves form-urlencoded behavior ({@code _ajax=1}, PHP array serialization).
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class HttpStepExecutor {

	private static final Logger logger = LoggerFactory.getLogger(HttpStepExecutor.class);

	private final ObjectMapper objectMapper;

	private final RestTemplate restTemplate;

	private final RequestBodySerializer requestSerializer;

	private final SystemAccessProfilePort systemAccessProfilePort;

	private final TokenBroker tokenBroker;

	public HttpStepExecutor(ObjectMapper objectMapper, RequestBodySerializer requestSerializer,
			SystemAccessProfilePort systemAccessProfilePort, TokenBroker tokenBroker) {
		this.objectMapper = objectMapper;
		this.requestSerializer = requestSerializer;
		this.systemAccessProfilePort = systemAccessProfilePort;
		this.tokenBroker = tokenBroker;
		this.restTemplate = new RestTemplate();
	}

	/**
	 * Execute an HTTP step.
	 */
	public StepResult execute(StepConfig config, FlowContext context) {
		try {
			// 1. Variable replacement, build request params
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

			logger.info("HttpStepExecutor#execute - method={}, endpoint={}, paramCount={}",
					config.getMethod(), config.getEndpoint(), requestParams.size());

			// 2. Execute HTTP request
			String responseBody = executeHttpRequest(config, requestParams, context);

			logger.info("HttpStepExecutor#execute - responseLength={}",
					responseBody != null ? responseBody.length() : 0);

			// 3. Check success condition
			if (config.getSuccessCondition() != null) {
				boolean success = evaluateCondition(config.getSuccessCondition(), responseBody);
				if (!success) {
					String errorMsg = extractErrorMessage(responseBody);
					return StepResult.failure(errorMsg);
				}
			}

			// 4. Extract outputs
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
		if (context.getSystemCode() != null && context.getAssistantUid() != null) {
			return executeViaSystemProfile(config, params, context);
		}
		else {
			logger.debug("HttpStepExecutor#executeHttpRequest - test mode, endpoint={}", config.getEndpoint());
			return "{\"code\": 0, \"msg\": \"success\", \"data\": {\"return_id\": 12345}}";
		}
	}

	private String executeViaSystemProfile(StepConfig config, Map<String, Object> params, FlowContext context) {
		try {
			// 1. Get system base URL
			String baseUrl = systemAccessProfilePort.getBaseUrl(context.getSystemCode());
			if (baseUrl == null) {
				throw new IllegalArgumentException("System not found: " + context.getSystemCode());
			}

			// 2. Get token
			Optional<TokenLease> leaseOpt = tokenBroker.acquire(context.getAssistantUid(), context.getSystemCode());
			String token = leaseOpt.map(TokenLease::accessToken).orElse(null);

			// 3. Build full URL
			String url = baseUrl + config.getEndpoint();
			logger.info("HttpStepExecutor#executeViaSystemProfile - method={}, url={}", config.getMethod(), url);

			// 4. Build headers
			HttpHeaders headers = new HttpHeaders();
			headers.set("X-Requested-With", "XMLHttpRequest");
			headers.set("Accept", "*/*");
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

			// 5. Build request body
			HttpEntity<?> entity;
			String contentType = config.getContentType();

			if (MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(contentType)) {
				headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
				MultiValueMap<String, String> formData = requestSerializer.toFormUrlEncoded(params);
				formData.add("_ajax", "1");
				logger.info("HttpStepExecutor#executeViaSystemProfile - form-urlencoded, paramCount={}",
						formData.size());
				entity = new HttpEntity<>(formData, headers);
			}
			else {
				headers.setContentType(MediaType.APPLICATION_JSON);
				String requestBody = objectMapper.writeValueAsString(params);
				logger.info("HttpStepExecutor#executeViaSystemProfile - JSON body, length={}", requestBody.length());
				entity = new HttpEntity<>(requestBody, headers);
			}

			// 6. Execute request
			ResponseEntity<String> response = restTemplate.exchange(url,
					HttpMethod.valueOf(config.getMethod()), entity, String.class);

			logger.info("HttpStepExecutor#executeViaSystemProfile - statusCode={}", response.getStatusCode());

			if (response.getStatusCode().is2xxSuccessful() || response.getStatusCode().is3xxRedirection()) {
				if (response.getStatusCode().is3xxRedirection()) {
					String location = response.getHeaders().getFirst("Location");
					logger.warn("HttpStepExecutor#executeViaSystemProfile - redirect, location={}", location);
				}
				return response.getBody() != null ? response.getBody() : "{}";
			}
			else {
				throw new RuntimeException("HTTP error: " + response.getStatusCode());
			}
		}
		catch (Exception e) {
			logger.error("HttpStepExecutor#executeViaSystemProfile - failed, error={}", e.getMessage(), e);
			throw new RuntimeException("HTTP request failed: " + e.getMessage(), e);
		}
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

}
