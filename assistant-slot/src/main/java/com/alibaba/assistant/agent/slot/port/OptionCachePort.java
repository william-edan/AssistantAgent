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

import java.time.Duration;
import java.util.Optional;

/**
 * Port interface for caching slot options.
 * Abstracts the Redis dependency so that the slot module
 * does not couple to a specific cache implementation.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface OptionCachePort {

	/**
	 * Get a cached value by key.
	 * @param key cache key
	 * @return cached value, or empty if not found or expired
	 */
	Optional<String> get(String key);

	/**
	 * Put a value in cache with TTL.
	 * @param key cache key
	 * @param value value to cache
	 * @param ttl time-to-live
	 */
	void put(String key, String value, Duration ttl);

}
