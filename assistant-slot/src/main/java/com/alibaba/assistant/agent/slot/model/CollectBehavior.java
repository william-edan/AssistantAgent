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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Collect phase behavior configuration.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class CollectBehavior {

	@JsonProperty("batch_size")
	private int batchSize;

	@JsonProperty("max_rounds")
	private int maxRounds;

	@JsonProperty("ask_mode")
	private SlotAskMode askMode;

	@JsonProperty("allow_partial_submit")
	private boolean allowPartialSubmit;

	@JsonProperty("timeout_action")
	private String timeoutAction;

	@JsonProperty("timeout_seconds")
	private int timeoutSeconds;

	public CollectBehavior() {
		this.batchSize = 3;
		this.maxRounds = 5;
		this.askMode = SlotAskMode.BATCH;
		this.allowPartialSubmit = false;
		this.timeoutAction = "CANCEL";
		this.timeoutSeconds = 300;
	}

	public static CollectBehavior defaults() {
		return new CollectBehavior();
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public int getMaxRounds() {
		return maxRounds;
	}

	public void setMaxRounds(int maxRounds) {
		this.maxRounds = maxRounds;
	}

	public SlotAskMode getAskMode() {
		return askMode;
	}

	public void setAskMode(SlotAskMode askMode) {
		this.askMode = askMode;
	}

	public boolean isAllowPartialSubmit() {
		return allowPartialSubmit;
	}

	public void setAllowPartialSubmit(boolean allowPartialSubmit) {
		this.allowPartialSubmit = allowPartialSubmit;
	}

	public String getTimeoutAction() {
		return timeoutAction;
	}

	public void setTimeoutAction(String timeoutAction) {
		this.timeoutAction = timeoutAction;
	}

	public int getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		this.timeoutSeconds = timeoutSeconds;
	}

}
