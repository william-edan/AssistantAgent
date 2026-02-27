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
import com.alibaba.assistant.agent.slot.port.OptionCachePort;
import com.alibaba.assistant.agent.slot.port.SystemAccessProfilePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for enriching slots with dynamic options from data sources.
 * Fetches options from external APIs and prepares them for:
 * 1. Frontend rendering (dropdown, radio buttons, etc.)
 * 2. LLM prompt enhancement (provide available options for better matching)
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class SlotEnricherService {

	private static final Logger logger = LoggerFactory.getLogger(SlotEnricherService.class);

	private static final String CACHE_KEY_PREFIX = "assistant:slot:options:";

	private static final Duration CACHE_TTL = Duration.ofMinutes(10);

	private final SystemAccessProfilePort systemAccessProfilePort;

	private final TokenBroker tokenBroker;

	private final ObjectMapper objectMapper;

	private final RestTemplate restTemplate;

	private final OptionCachePort optionCachePort;

	public SlotEnricherService(SystemAccessProfilePort systemAccessProfilePort, TokenBroker tokenBroker,
			ObjectMapper objectMapper, OptionCachePort optionCachePort) {
		this.systemAccessProfilePort = systemAccessProfilePort;
		this.tokenBroker = tokenBroker;
		this.objectMapper = objectMapper;
		this.restTemplate = new RestTemplate();
		this.optionCachePort = optionCachePort;
	}

	/**
	 * Enrich slots with dynamic options from data sources.
	 *
	 * @param slots list of slot definitions to enrich
	 * @param systemCode system code for API calls
	 * @param userId user ID for authentication
	 * @return list of enriched slots with loaded options
	 */
	public List<EnrichedSlot> enrichSlots(List<SlotDefinition> slots, String systemCode, String userId) {
		if (slots == null || slots.isEmpty()) {
			return Collections.emptyList();
		}

		logger.debug("SlotEnricherService#enrichSlots - slotsCount={}, systemCode={}, userId={}", slots.size(),
				systemCode, userId);

		List<EnrichedSlot> enrichedSlots = new ArrayList<>();

		for (SlotDefinition slot : slots) {
			EnrichedSlot enriched = new EnrichedSlot(slot);

			SlotOptions optionsConfig = slot.getOptions();
			if (optionsConfig != null && optionsConfig.getSource() == SlotOptions.SourceType.API) {
				try {
					List<SlotOption> options = loadOptionsFromApi(slot, optionsConfig, systemCode, userId);
					enriched.setOptions(options);
				}
				catch (Exception e) {
					logger.warn("SlotEnricherService#enrichSlots - failed to load API options, slot={}, error={}",
							slot.getName(), e.getMessage());
					enriched.setOptionsError(e.getMessage());

					// Fallback: use enumMapping if available
					if (optionsConfig.getEnumMapping() != null && !optionsConfig.getEnumMapping().isEmpty()) {
						List<SlotOption> fallbackOptions = convertEnumMappingToOptions(optionsConfig.getEnumMapping());
						enriched.setOptions(fallbackOptions);
						logger.info("SlotEnricherService#enrichSlots - enumMapping fallback, slot={}, count={}",
								slot.getName(), fallbackOptions.size());
					}
				}
			}
			else if (optionsConfig != null && optionsConfig.getEnumMapping() != null) {
				List<SlotOption> options = convertEnumMappingToOptions(optionsConfig.getEnumMapping());
				enriched.setOptions(options);
			}

			enrichedSlots.add(enriched);
		}

		return enrichedSlots;
	}

	private List<SlotOption> loadOptionsFromApi(SlotDefinition slot, SlotOptions optionsConfig, String systemCode,
			String userId) throws Exception {

		SlotOptions.ApiConfig apiConfig = optionsConfig.getApiConfig();
		if (apiConfig == null) {
			throw new IllegalStateException("API configuration is missing for slot: " + slot.getName());
		}

		String endpoint = apiConfig.getEndpoint();
		String method = apiConfig.getMethod() != null ? apiConfig.getMethod() : "GET";
		String labelField = apiConfig.getLabelField();
		String valueField = apiConfig.getValueField();
		Map<String, String> params = apiConfig.getParams();

		// Check cache
		String cacheKey = buildCacheKey(systemCode, userId, slot.getName(), endpoint);

		try {
			Optional<String> cachedJson = optionCachePort.get(cacheKey);
			if (cachedJson.isPresent()) {
				List<SlotOption> cachedOptions = objectMapper.readValue(cachedJson.get(),
						new TypeReference<List<SlotOption>>() {
						});
				logger.debug("SlotEnricherService#loadOptionsFromApi - cache hit, slot={}, count={}", slot.getName(),
						cachedOptions.size());
				return cachedOptions;
			}
		}
		catch (Exception e) {
			logger.warn("SlotEnricherService#loadOptionsFromApi - cache read failed, slot={}, error={}",
					slot.getName(), e.getMessage());
		}

		// Get system base URL
		String baseUrl = systemAccessProfilePort.getBaseUrl(systemCode);
		if (baseUrl == null) {
			throw new IllegalStateException("System not found: " + systemCode);
		}

		// Get token
		Optional<TokenLease> leaseOpt = tokenBroker.acquire(userId, systemCode);
		String token = leaseOpt.map(TokenLease::accessToken).orElse(null);

		String fullUrl = baseUrl + endpoint;

		// Build request
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			String headerName = systemAccessProfilePort.getTokenHeaderName(systemCode);
			String headerPrefix = systemAccessProfilePort.getTokenHeaderPrefix(systemCode);
			headers.set(headerName != null ? headerName : "Authorization",
					(headerPrefix != null ? headerPrefix : "Bearer ") + token);
		}

		// Make API call
		ResponseEntity<String> response;
		if ("POST".equalsIgnoreCase(method)) {
			HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(params, headers);
			response = restTemplate.exchange(fullUrl, HttpMethod.POST, requestEntity, String.class);
		}
		else {
			if (params != null && !params.isEmpty()) {
				fullUrl = appendQueryParams(fullUrl, params);
			}
			HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
			response = restTemplate.exchange(fullUrl, HttpMethod.GET, requestEntity, String.class);
		}

		if (!response.getStatusCode().is2xxSuccessful()) {
			throw new RuntimeException("API call failed: " + response.getStatusCode());
		}

		String responseBody = response.getBody();
		List<SlotOption> options = parseOptionsFromResponse(responseBody, labelField, valueField);

		// Cache result
		try {
			String optionsJson = objectMapper.writeValueAsString(options);
			optionCachePort.put(cacheKey, optionsJson, CACHE_TTL);
		}
		catch (Exception e) {
			logger.warn("SlotEnricherService#loadOptionsFromApi - cache write failed, slot={}, error={}",
					slot.getName(), e.getMessage());
		}

		return options;
	}

	@SuppressWarnings("unchecked")
	private List<SlotOption> parseOptionsFromResponse(String responseBody, String labelField, String valueField)
			throws Exception {
		if (responseBody == null || responseBody.trim().isEmpty()) {
			return Collections.emptyList();
		}

		Map<String, Object> responseMap = objectMapper.readValue(responseBody,
				new TypeReference<Map<String, Object>>() {
				});

		Object dataObj = responseMap.get("data");
		if (dataObj == null) {
			return Collections.emptyList();
		}

		if (!(dataObj instanceof List)) {
			return Collections.emptyList();
		}

		List<Map<String, Object>> dataList = (List<Map<String, Object>>) dataObj;
		List<SlotOption> options = new ArrayList<>();
		for (Map<String, Object> item : dataList) {
			Object label = item.get(labelField);
			Object value = item.get(valueField);

			if (label != null && value != null) {
				options.add(new SlotOption(label.toString(), value));
			}
		}

		return options;
	}

	private List<SlotOption> convertEnumMappingToOptions(Map<String, Object> enumMapping) {
		if (enumMapping == null || enumMapping.isEmpty()) {
			return Collections.emptyList();
		}

		return enumMapping.entrySet()
			.stream()
			.map(entry -> new SlotOption(entry.getKey(), entry.getValue()))
			.collect(Collectors.toList());
	}

	private String appendQueryParams(String url, Map<String, String> params) {
		if (params == null || params.isEmpty()) {
			return url;
		}

		StringBuilder sb = new StringBuilder(url);
		sb.append(url.contains("?") ? "&" : "?");

		boolean first = true;
		for (Map.Entry<String, String> entry : params.entrySet()) {
			if (!first) {
				sb.append("&");
			}
			sb.append(entry.getKey()).append("=").append(entry.getValue());
			first = false;
		}

		return sb.toString();
	}

	private String buildCacheKey(String systemCode, String userId, String slotName, String endpoint) {
		String cleanEndpoint = endpoint.replaceAll("[^a-zA-Z0-9/_-]", "_");
		return CACHE_KEY_PREFIX + systemCode + ":" + userId + ":" + slotName + ":" + cleanEndpoint;
	}

}
