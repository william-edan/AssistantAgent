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
package com.alibaba.assistant.agent.api.controller.dto;

import java.time.LocalDateTime;

/**
 * 会话操作响应 - 用于更新/删除/置顶等操作。
 *
 * <p>包含操作后的会话状态，便于前端同步更新。</p>
 *
 * @param code      响应码（0=成功）
 * @param msg       响应消息
 * @param threadId  会话ID
 * @param title     当前标题（更新后）
 * @param pinned    当前置顶状态
 * @param pinnedAt  置顶时间（如置顶）
 * @param updatedAt 更新时间
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record ChatThreadOperationResponse(
		int code,
		String msg,
		String threadId,
		String title,
		Boolean pinned,
		LocalDateTime pinnedAt,
		LocalDateTime updatedAt) {

	/**
	 * 成功响应工厂方法。
	 *
	 * @param threadId  会话ID
	 * @param title     标题
	 * @param pinned    置顶状态
	 * @param pinnedAt  置顶时间
	 * @param updatedAt 更新时间
	 * @return 成功响应
	 */
	public static ChatThreadOperationResponse ok(
			String threadId,
			String title,
			Boolean pinned,
			LocalDateTime pinnedAt,
			LocalDateTime updatedAt) {
		return new ChatThreadOperationResponse(0, "success", threadId, title, pinned, pinnedAt, updatedAt);
	}

	/**
	 * 删除成功响应。
	 *
	 * @param threadId 会话ID
	 * @return 删除成功响应
	 */
	public static ChatThreadOperationResponse deleted(String threadId) {
		return new ChatThreadOperationResponse(0, "deleted", threadId, null, null, null, null);
	}

	/**
	 * 错误响应。
	 *
	 * @param code 错误码
	 * @param msg  错误消息
	 * @return 错误响应
	 */
	public static ChatThreadOperationResponse error(int code, String msg) {
		return new ChatThreadOperationResponse(code, msg, null, null, null, null, null);
	}
}
