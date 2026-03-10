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
package com.alibaba.assistant.agent.runtime.execution;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared credential resolution request for artifact-native execution.
 */
public record CredentialResolutionRequest(
		Long spaceId,
		Long connectorId,
		List<String> candidateAuthProfileCodes,
		String platformPrincipalId,
		String platformPrincipalType,
		List<String> requestedScopes,
		String runId,
		String stepId,
		String compatibilitySystemCode) {

	public CredentialResolutionRequest {
		candidateAuthProfileCodes = normalize(candidateAuthProfileCodes);
		requestedScopes = normalize(requestedScopes);
	}

	private static List<String> normalize(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null) {
				continue;
			}
			String trimmed = value.trim();
			if (!trimmed.isEmpty()) {
				normalized.add(trimmed);
			}
		}
		return List.copyOf(normalized);
	}
}
