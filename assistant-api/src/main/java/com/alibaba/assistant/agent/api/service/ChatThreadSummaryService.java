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
package com.alibaba.assistant.agent.api.service;

import com.alibaba.assistant.agent.api.controller.dto.ChatThreadSummaryListData;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 聊天会话汇总服务接口 - 高性能查询与管理。
 *
 * <p>提供会话列表查询、标题修改、置顶管理、删除等核心能力，
 * 所有方法均为用户级隔离，确保数据安全。</p>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface ChatThreadSummaryService {

	/**
	 * 分页查询用户的会话汇总列表。
	 *
	 * <p>返回结果按置顶优先、时间倒序排列：
	 * 1. 置顶会话（按 pinnedAt 降序）
	 * 2. 普通会话（按 updatedAt 降序）</p>
	 *
	 * @param assistantUid 用户ID（必填）
	 * @param limit        查询数量（默认20，最大100）
	 * @param offset       偏移量（用于分页）
	 * @return 会话列表数据（置顶和普通分开）
	 */
	ChatThreadSummaryListData listSummaries(String assistantUid, Integer limit, Integer offset);

	/**
	 * 修改会话标题。
	 *
	 * @param assistantUid 用户ID
	 * @param threadId     会话ID
	 * @param newTitle     新标题
	 * @return 更新后的标题（如果会话不存在或无权访问返回 empty）
	 */
	Optional<String> updateTitle(String assistantUid, String threadId, String newTitle);

	/**
	 * 置顶或取消置顶会话。
	 *
	 * @param assistantUid 用户ID
	 * @param threadId     会话ID
	 * @param pinned       true=置顶, false=取消置顶
	 * @return 置顶时间（取消置顶返回 null）
	 */
	Optional<LocalDateTime> updatePinStatus(String assistantUid, String threadId, boolean pinned);

	/**
	 * 删除会话（软删除或硬删除，取决于实现）。
	 *
	 * @param assistantUid 用户ID
	 * @param threadId     会话ID
	 * @return true=删除成功, false=会话不存在或无权访问
	 */
	boolean deleteThread(String assistantUid, String threadId);

	/**
	 * 批量删除会话。
	 *
	 * @param assistantUid 用户ID
	 * @param threadIds    会话ID列表
	 * @return 成功删除的数量
	 */
	int batchDeleteThreads(String assistantUid, java.util.List<String> threadIds);

	/**
	 * 获取单个会话汇总信息。
	 *
	 * @param assistantUid 用户ID
	 * @param threadId     会话ID
	 * @return 会话汇总数据
	 */
	Optional<com.alibaba.assistant.agent.api.controller.dto.ChatThreadSummaryViewData> getSummary(
			String assistantUid, String threadId);
}
