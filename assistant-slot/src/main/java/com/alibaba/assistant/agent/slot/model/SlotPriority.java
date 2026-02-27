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
 * Slot collection priority.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public enum SlotPriority {
	/**
	 * Core slots - must be collected, flow cannot proceed without them
	 */
	CORE,

	/**
	 * Confirm slots - should be collected for confirmation, but can use defaults
	 */
	CONFIRM,

	/**
	 * Optional slots - nice to have, can be skipped
	 */
	OPTIONAL,

	/**
	 * Supplementary slots - lowest priority, typically not asked proactively
	 * Examples: CC recipients, additional notes, optional attachments
	 */
	SUPPLEMENTARY
}
