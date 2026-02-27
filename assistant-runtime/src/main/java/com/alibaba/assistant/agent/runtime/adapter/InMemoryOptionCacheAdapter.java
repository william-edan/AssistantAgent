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
package com.alibaba.assistant.agent.runtime.adapter;

import com.alibaba.assistant.agent.slot.port.OptionCachePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory OptionCachePort implementation for migration profile.
 * Provides TTL behavior without requiring Redis in local development.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class InMemoryOptionCacheAdapter implements OptionCachePort {

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value());
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        Duration effectiveTtl = ttl != null ? ttl : Duration.ofMinutes(10);
        cache.put(key, new CacheEntry(value, Instant.now().plus(effectiveTtl)));
    }

    private record CacheEntry(String value, Instant expiresAt) {
    }
}
