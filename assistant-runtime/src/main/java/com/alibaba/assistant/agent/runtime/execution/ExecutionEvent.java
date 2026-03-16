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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原生执行链输出的稳定事件包。
 */
public record ExecutionEvent(
		String runId,
		String artifactCode,
		String artifactType,
		String stepId,
		long sequence,
		ExecutionEventType eventType,
		ExecutionLifecycleStatus lifecycleStatus,
		Instant occurredAt,
		Map<String, Object> payload) {

	public ExecutionEvent {
		payload = Map.copyOf(payload != null ? new LinkedHashMap<>(payload) : Map.of());
	}
}
