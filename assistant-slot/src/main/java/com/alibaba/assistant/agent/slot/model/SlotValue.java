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

import java.time.LocalDateTime;

/**
 * Collected slot value with metadata.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SlotValue {

	public enum Source {
		/** User provided value */
		USER,
		/** Resolved from API */
		API,
		/** Default value */
		DEFAULT,
		/** Auto-selected value */
		AUTO,
		/** Inferred from context */
		INFERRED
	}

	private String slotName;

	private Object rawValue;

	private Object resolvedValue;

	private String displayValue;

	private Source source;

	private boolean confirmed;

	private LocalDateTime collectedAt;

	private int collectRound;

	public SlotValue() {
		this.collectedAt = LocalDateTime.now();
	}

	public static SlotValue fromUser(String slotName, Object value) {
		SlotValue sv = new SlotValue();
		sv.setSlotName(slotName);
		sv.setRawValue(value);
		sv.setResolvedValue(value);
		sv.setSource(Source.USER);
		return sv;
	}

	public static SlotValue fromDefault(String slotName, Object value) {
		SlotValue sv = new SlotValue();
		sv.setSlotName(slotName);
		sv.setRawValue(value);
		sv.setResolvedValue(value);
		sv.setSource(Source.DEFAULT);
		return sv;
	}

	public static SlotValue resolved(String slotName, Object rawValue, Object resolvedValue, String displayValue) {
		SlotValue sv = new SlotValue();
		sv.setSlotName(slotName);
		sv.setRawValue(rawValue);
		sv.setResolvedValue(resolvedValue);
		sv.setDisplayValue(displayValue);
		sv.setSource(Source.API);
		return sv;
	}

	public String getSlotName() {
		return slotName;
	}

	public void setSlotName(String slotName) {
		this.slotName = slotName;
	}

	public Object getRawValue() {
		return rawValue;
	}

	public void setRawValue(Object rawValue) {
		this.rawValue = rawValue;
	}

	public Object getResolvedValue() {
		return resolvedValue;
	}

	public void setResolvedValue(Object resolvedValue) {
		this.resolvedValue = resolvedValue;
	}

	public String getDisplayValue() {
		return displayValue;
	}

	public void setDisplayValue(String displayValue) {
		this.displayValue = displayValue;
	}

	public Source getSource() {
		return source;
	}

	public void setSource(Source source) {
		this.source = source;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	public LocalDateTime getCollectedAt() {
		return collectedAt;
	}

	public void setCollectedAt(LocalDateTime collectedAt) {
		this.collectedAt = collectedAt;
	}

	public int getCollectRound() {
		return collectRound;
	}

	public void setCollectRound(int collectRound) {
		this.collectRound = collectRound;
	}

}
