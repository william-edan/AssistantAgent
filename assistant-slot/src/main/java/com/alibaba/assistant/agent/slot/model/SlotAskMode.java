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

/**
 * Slot collection ask mode.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public enum SlotAskMode {
	/**
	 * Batch mode - ask multiple slots together in one round
	 */
	BATCH,

	/**
	 * Single mode - ask one slot at a time
	 */
	SINGLE,

	/**
	 * Form only mode - only show in form, don't ask in conversation
	 */
	FORM_ONLY,

	/**
	 * Auto mode - automatically select based on slot type and context
	 */
	AUTO
}
