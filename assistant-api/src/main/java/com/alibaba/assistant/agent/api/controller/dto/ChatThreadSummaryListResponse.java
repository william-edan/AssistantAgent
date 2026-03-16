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

/**
 * 聊天会话汇总列表响应 - 标准 REST 响应结构。
 *
 * <p>遵循项目统一的响应格式：code + msg + data，
 * code 为 0 表示成功，非 0 表示业务错误。</p>
 *
 * @param code 响应码（0=成功）
 * @param msg  响应消息
 * @param data 响应数据
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record ChatThreadSummaryListResponse(int code, String msg, ChatThreadSummaryListData data) {

	/**
	 * 成功响应工厂方法。
	 *
	 * @param data 列表数据
	 * @return 成功响应实例
	 */
	public static ChatThreadSummaryListResponse ok(ChatThreadSummaryListData data) {
		return new ChatThreadSummaryListResponse(0, "success", data);
	}

	/**
	 * 成功响应 - 空数据。
	 *
	 * @return 空数据成功响应
	 */
	public static ChatThreadSummaryListResponse ok() {
		return ok(ChatThreadSummaryListData.empty());
	}

	/**
	 * 错误响应工厂方法。
	 *
	 * @param code 错误码
	 * @param msg  错误消息
	 * @return 错误响应实例
	 */
	public static ChatThreadSummaryListResponse error(int code, String msg) {
		return new ChatThreadSummaryListResponse(code, msg, null);
	}
}
