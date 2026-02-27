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
package com.alibaba.assistant.agent.slot.port;

/**
 * Port interface for accessing system access profile data.
 * Abstracts the old SystemRegistryService direct DB dependency so that
 * the slot module does not couple to the persistence layer.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface SystemAccessProfilePort {

	/**
	 * Get the base URL for a given system code.
	 * @param systemCode the system code
	 * @return base URL or null if not found
	 */
	String getBaseUrl(String systemCode);

	/**
	 * Get the token header name for a given system code.
	 * @param systemCode the system code
	 * @return header name (e.g., "Authorization"), or null for default
	 */
	String getTokenHeaderName(String systemCode);

	/**
	 * Get the token header prefix for a given system code.
	 * @param systemCode the system code
	 * @return header prefix (e.g., "Bearer "), or null for default
	 */
	String getTokenHeaderPrefix(String systemCode);

}
