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
package com.alibaba.assistant.agent.slot;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.slot.model.*;
import com.alibaba.assistant.agent.slot.port.SystemAccessProfilePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for resolving slot values.
 * Handles value resolution from user input: name-to-ID mapping, date parsing, API queries.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class SlotResolverService {

	private static final Logger logger = LoggerFactory.getLogger(SlotResolverService.class);

	private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

	private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
			DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("yyyy/MM/dd"),
			DateTimeFormatter.ofPattern("yyyy年MM月dd日"), DateTimeFormatter.ofPattern("MM-dd"),
			DateTimeFormatter.ofPattern("MM/dd"));

	private final SystemAccessProfilePort systemAccessProfilePort;

	private final TokenBroker tokenBroker;

	private final ObjectMapper objectMapper;

	private final RestTemplate restTemplate;

	public SlotResolverService(SystemAccessProfilePort systemAccessProfilePort, TokenBroker tokenBroker,
			ObjectMapper objectMapper) {
		this.systemAccessProfilePort = systemAccessProfilePort;
		this.tokenBroker = tokenBroker;
		this.objectMapper = objectMapper;
		this.restTemplate = new RestTemplate();
	}

	/**
	 * Load options from API (for dropdowns, not search).
	 *
	 * @param slot slot definition with API config
	 * @param context resolver context
	 * @return list of option values
	 */
	public List<SlotOptions.OptionValue> loadOptionsFromApi(SlotDefinition slot, ResolverContext context) {
		if (slot == null || !slot.hasOptions() || slot.getOptions().getSource() != SlotOptions.SourceType.API
				|| slot.getOptions().getApiConfig() == null) {
			return Collections.emptyList();
		}

		SlotOptions.ApiConfig apiConfig = slot.getOptions().getApiConfig();
		String slotName = slot.getName();

		try {
			String systemCode = context.getSystemCode();
			String assistantUid = context.getAssistantUid();

			logger.info("SlotResolverService#loadOptionsFromApi - slotName={}, systemCode={}, assistantUid={}",
					slotName, systemCode, assistantUid);

			String baseUrl = systemAccessProfilePort.getBaseUrl(systemCode);
			if (baseUrl == null) {
				logger.warn("SlotResolverService#loadOptionsFromApi - system not found, systemCode={}", systemCode);
				return Collections.emptyList();
			}

			Optional<TokenLease> leaseOpt = tokenBroker.acquire(assistantUid, systemCode);
			String token = leaseOpt.map(TokenLease::accessToken).orElse(null);

			// Build URL with params
			String url = baseUrl + apiConfig.getEndpoint();
			Map<String, String> params = new HashMap<>();
			if (apiConfig.getParams() != null) {
				for (Map.Entry<String, String> entry : apiConfig.getParams().entrySet()) {
					String value = entry.getValue();
					if (value.contains("${")) {
						value = resolveVariables(value, "", context);
					}
					params.put(entry.getKey(), value);
				}
			}

			StringBuilder queryString = new StringBuilder();
			for (Map.Entry<String, String> entry : params.entrySet()) {
				if (queryString.length() > 0) {
					queryString.append("&");
				}
				queryString.append(entry.getKey()).append("=").append(entry.getValue());
			}
			if (queryString.length() > 0) {
				url += "?" + queryString;
			}

			// Build request headers
			HttpHeaders headers = new HttpHeaders();
			headers.set("X-Requested-With", "XMLHttpRequest");
			if (token != null) {
				String headerName = systemAccessProfilePort.getTokenHeaderName(systemCode);
				String headerPrefix = systemAccessProfilePort.getTokenHeaderPrefix(systemCode);
				headers.set(headerName != null ? headerName : "Authorization",
						(headerPrefix != null ? headerPrefix : "Bearer ") + token);
			}

			HttpEntity<String> entity = new HttpEntity<>(headers);
			ResponseEntity<String> response = restTemplate.exchange(url,
					HttpMethod.valueOf(apiConfig.getMethod() != null ? apiConfig.getMethod() : "GET"), entity,
					String.class);

			if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
				String body = response.getBody();
				if (body.contains("请先登录") || body.contains("未登录")) {
					logger.error("SlotResolverService#loadOptionsFromApi - authentication failed for slot={}", slotName);
					return Collections.emptyList();
				}
				return parseOptionsFromResponse(body, apiConfig);
			}
		}
		catch (Exception e) {
			logger.error("SlotResolverService#loadOptionsFromApi - API call failed, slotName={}, error={}", slotName,
					e.getMessage(), e);
		}

		return Collections.emptyList();
	}

	/**
	 * Resolve user by searching employees.
	 */
	public SlotValue resolveUser(SlotDefinition slot, String searchTerm, String systemCode, String assistantUid) {
		return SlotValue.fromUser(slot.getName(), searchTerm);
	}

	@SuppressWarnings("unchecked")
	private List<SlotOptions.OptionValue> parseOptionsFromResponse(String responseBody,
			SlotOptions.ApiConfig apiConfig) {
		List<SlotOptions.OptionValue> options = new ArrayList<>();

		try {
			Map<String, Object> responseMap = objectMapper.readValue(responseBody,
					new TypeReference<Map<String, Object>>() {
					});

			Object data = responseMap.get("data");
			if (data == null) {
				data = responseMap.get("list");
			}
			if (data == null) {
				data = responseMap.get("items");
			}

			if (data instanceof List) {
				List<Map<String, Object>> items = (List<Map<String, Object>>) data;
				String valueField = apiConfig.getValueField() != null ? apiConfig.getValueField() : "id";
				String labelField = apiConfig.getLabelField() != null ? apiConfig.getLabelField() : "name";

				for (Map<String, Object> item : items) {
					SlotOptions.OptionValue option = parseOptionFromItem(item, apiConfig, valueField, labelField);
					if (option != null) {
						options.add(option);
					}
				}
			}
		}
		catch (Exception e) {
			logger.warn("SlotResolverService#parseOptionsFromResponse - failed to parse response, error={}",
					e.getMessage());
		}

		return options;
	}

	@SuppressWarnings("unchecked")
	private SlotOptions.OptionValue parseOptionFromItem(Map<String, Object> item, SlotOptions.ApiConfig apiConfig,
			String valueField, String labelField) {
		Object value = item.get(valueField);
		Object label = item.get(labelField);

		if (value == null || label == null) {
			return null;
		}

		SlotOptions.OptionValue option = new SlotOptions.OptionValue();
		option.setValue(value);
		option.setLabel(label.toString());

		if (apiConfig.getExtraFields() != null) {
			Map<String, Object> extras = new HashMap<>();
			for (String field : apiConfig.getExtraFields()) {
				if (item.containsKey(field)) {
					extras.put(field, item.get(field));
				}
			}
			option.setExtras(extras);
		}

		if (apiConfig.isTreeStructure() && apiConfig.getChildrenField() != null) {
			String childrenField = apiConfig.getChildrenField();
			Object childrenObj = item.get(childrenField);

			if (childrenObj instanceof List) {
				List<Map<String, Object>> childrenItems = (List<Map<String, Object>>) childrenObj;
				List<SlotOptions.OptionValue> children = new ArrayList<>();

				for (Map<String, Object> childItem : childrenItems) {
					SlotOptions.OptionValue child = parseOptionFromItem(childItem, apiConfig, valueField, labelField);
					if (child != null) {
						children.add(child);
					}
				}

				if (!children.isEmpty()) {
					option.setChildren(children);
				}
			}
		}

		return option;
	}

	/**
	 * Replace variables in string with actual values.
	 */
	private String resolveVariables(String template, Object userInput, ResolverContext context) {
		Matcher matcher = VARIABLE_PATTERN.matcher(template);
		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String varName = matcher.group(1);
			String replacement;

			if ("search_keyword".equals(varName) || "keyword".equals(varName)) {
				replacement = userInput.toString();
			}
			else if (context.getCollectedSlots().containsKey(varName)) {
				replacement = context.getCollectedSlots().get(varName).getResolvedValue().toString();
			}
			else if (context.getCollectedParams().containsKey(varName)) {
				replacement = context.getCollectedParams().get(varName).toString();
			}
			else {
				replacement = "";
			}

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);

		return result.toString();
	}

}
