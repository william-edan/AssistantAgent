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

import java.util.List;

/**
 * 聊天会话汇总列表数据 - 包装分页结果。
 *
 * <p>使用 Java 17 Record 实现不可变数据结构，
 * 包含置顶会话列表和普通会话列表，前端可直接渲染。</p>
 *
 * @param pinnedThreads   置顶会话列表（按 pinnedAt 降序）
 * @param normalThreads   普通会话列表（按 updatedAt 降序）
 * @param total           总会话数
 * @param pinnedCount     置顶会话数
 * @param hasMore         是否还有更多数据
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record ChatThreadSummaryListData(
		List<ChatThreadSummaryViewData> pinnedThreads,
		List<ChatThreadSummaryViewData> normalThreads,
		long total,
		long pinnedCount,
		boolean hasMore) {

	/**
	 * 创建空列表数据。
	 *
	 * @return 空列表数据实例
	 */
	public static ChatThreadSummaryListData empty() {
		return new ChatThreadSummaryListData(List.of(), List.of(), 0L, 0L, false);
	}

	/**
	 * 获取所有会话（置顶在前，普通在后）。
	 *
	 * @return 合并后的会话列表
	 */
	public List<ChatThreadSummaryViewData> allThreads() {
		return java.util.stream.Stream
				.concat(pinnedThreads.stream(), normalThreads.stream())
				.toList();
	}
}
