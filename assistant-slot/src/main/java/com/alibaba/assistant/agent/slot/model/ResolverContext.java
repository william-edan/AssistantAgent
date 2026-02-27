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
package com.alibaba.assistant.agent.slot.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolver context used by SlotResolverService and SlotEnricherService,
 * replacing the old ConversationContext dependency. Carries the minimal
 * set of data needed for value resolution and API calls.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ResolverContext {

	private String assistantUid;

	private String systemCode;

	private Map<String, SlotValue> collectedSlots;

	private Map<String, Object> collectedParams;

	public ResolverContext() {
		this.collectedSlots = new HashMap<>();
		this.collectedParams = new HashMap<>();
	}

	public ResolverContext(String assistantUid, String systemCode) {
		this();
		this.assistantUid = assistantUid;
		this.systemCode = systemCode;
	}

	public String getAssistantUid() {
		return assistantUid;
	}

	public void setAssistantUid(String assistantUid) {
		this.assistantUid = assistantUid;
	}

	public String getSystemCode() {
		return systemCode;
	}

	public void setSystemCode(String systemCode) {
		this.systemCode = systemCode;
	}

	public Map<String, SlotValue> getCollectedSlots() {
		return collectedSlots;
	}

	public void setCollectedSlots(Map<String, SlotValue> collectedSlots) {
		this.collectedSlots = collectedSlots;
	}

	public Map<String, Object> getCollectedParams() {
		return collectedParams;
	}

	public void setCollectedParams(Map<String, Object> collectedParams) {
		this.collectedParams = collectedParams;
	}

}
