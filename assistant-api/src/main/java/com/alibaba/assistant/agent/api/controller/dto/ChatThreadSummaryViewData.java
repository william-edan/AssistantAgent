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
 * 聊天会话汇总视图数据 - 用于会话列表展示。
 *
 * <p>该记录包含了会话的核心元数据、状态信息以及置顶相关属性，
 * 支持 Java 17 Record 不可变特性，确保线程安全。</p>
 *
 * @param threadId           会话唯一标识
 * @param title              会话标题（用户可修改）
 * @param lastMessagePreview 最后一条消息预览
 * @param status             会话状态（UNDERSTANDING/EXECUTING/COMPLETED等）
 * @param unfinished         是否未完成
 * @param pinned             是否置顶
 * @param pinnedAt           置顶时间（用于排序）
 * @param messageCount       消息数量
 * @param createdAt          创建时间
 * @param updatedAt          最后更新时间
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public record ChatThreadSummaryViewData(
		String threadId,
		String title,
		String lastMessagePreview,
		String status,
		Boolean unfinished,
		Boolean pinned,
		LocalDateTime pinnedAt,
		Integer messageCount,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	/**
	 * 创建 Builder 实例，支持链式构建。
	 *
	 * @return Builder 实例
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * 可变构建器模式 - 支持流畅的链式调用。
	 */
	public static class Builder {
		private String threadId;
		private String title;
		private String lastMessagePreview;
		private String status;
		private Boolean unfinished;
		private Boolean pinned;
		private LocalDateTime pinnedAt;
		private Integer messageCount;
		private LocalDateTime createdAt;
		private LocalDateTime updatedAt;

		public Builder threadId(String threadId) {
			this.threadId = threadId;
			return this;
		}

		public Builder title(String title) {
			this.title = title;
			return this;
		}

		public Builder lastMessagePreview(String lastMessagePreview) {
			this.lastMessagePreview = lastMessagePreview;
			return this;
		}

		public Builder status(String status) {
			this.status = status;
			return this;
		}

		public Builder unfinished(Boolean unfinished) {
			this.unfinished = unfinished;
			return this;
		}

		public Builder pinned(Boolean pinned) {
			this.pinned = pinned;
			return this;
		}

		public Builder pinnedAt(LocalDateTime pinnedAt) {
			this.pinnedAt = pinnedAt;
			return this;
		}

		public Builder messageCount(Integer messageCount) {
			this.messageCount = messageCount;
			return this;
		}

		public Builder createdAt(LocalDateTime createdAt) {
			this.createdAt = createdAt;
			return this;
		}

		public Builder updatedAt(LocalDateTime updatedAt) {
			this.updatedAt = updatedAt;
			return this;
		}

		public ChatThreadSummaryViewData build() {
			return new ChatThreadSummaryViewData(
					threadId, title, lastMessagePreview, status, unfinished,
					pinned, pinnedAt, messageCount, createdAt, updatedAt);
		}
	}
}
