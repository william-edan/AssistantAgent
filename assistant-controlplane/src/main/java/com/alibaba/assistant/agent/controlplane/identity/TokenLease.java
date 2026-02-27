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
package com.alibaba.assistant.agent.controlplane.identity;

import java.time.LocalDateTime;

/**
 * Represents a leased access token for a specific system.
 *
 * @param leaseId unique lease identifier
 * @param accessToken the access token value
 * @param systemCode the target system code
 * @param assistantUid the assistant user identifier
 * @param expiresAt token expiration time
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record TokenLease(
		String leaseId,
		String accessToken,
		String systemCode,
		String assistantUid,
		LocalDateTime expiresAt) {

	public boolean isExpired() {
		return LocalDateTime.now().isAfter(expiresAt);
	}

}
