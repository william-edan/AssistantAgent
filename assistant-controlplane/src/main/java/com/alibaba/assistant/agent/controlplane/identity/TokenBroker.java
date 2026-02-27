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

import java.util.Optional;

/**
 * Token broker interface for acquiring and managing access tokens
 * to downstream business systems on behalf of assistant users.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface TokenBroker {

	/**
	 * Acquire a valid token lease for the given user and system.
	 * May return a cached lease if still valid, or request a new one.
	 * @param assistantUid the assistant user identifier
	 * @param systemCode the target system code
	 * @return a valid token lease, or empty if credentials not available
	 */
	Optional<TokenLease> acquire(String assistantUid, String systemCode);

	/**
	 * Revoke/invalidate an existing token lease.
	 * @param leaseId the lease to revoke
	 */
	void revoke(String leaseId);

}
