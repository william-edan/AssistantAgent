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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.controller.dto.*;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatThreadSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 聊天会话汇总管理控制器 - RESTful API 设计。
 *
 * <p>【功能概览】
 * 提供会话列表查询、标题修改、置顶管理、删除等完整生命周期管理能力。
 * 所有接口均遵循 RESTful 设计规范，支持标准 HTTP 方法。</p>
 *
 * <p>【RESTful 端点映射】
 * <pre>
 * GET    /api/chat/threads/summaries          - 获取会话汇总列表
 * GET    /api/chat/threads/summaries/{id}     - 获取单个会话汇总
 * PATCH  /api/chat/threads/summaries/{id}     - 修改会话标题（部分更新）
 * PUT    /api/chat/threads/summaries/{id}/pin - 置顶/取消置顶会话
 * DELETE /api/chat/threads/summaries/{id}     - 删除单个会话
 * DELETE /api/chat/threads/summaries          - 批量删除会话
 * </pre></p>
 *
 * <p>【Java 17 特性应用】
 * - Record 类型：请求/响应 DTO 使用不可变 Record
 * - Pattern Matching：instanceof 自动类型转换
 * - Switch Expressions：状态映射（如需要）
 * - Text Blocks：SQL/JSON 字符串（如需要）</p>
 *
 * <p>【性能优化】
 * - 单次数据库查询获取列表，内存排序减少 IO
 * - 使用 Optional 避免空指针，减少异常开销
 * - 批量删除减少数据库往返</p>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat/threads/summaries")
public class ChatThreadSummaryController {

	private static final Logger logger = LoggerFactory.getLogger(ChatThreadSummaryController.class);

	/**
	 * 默认分页大小
	 */
	private static final int DEFAULT_LIMIT = 20;

	/**
	 * 最大分页大小
	 */
	private static final int MAX_LIMIT = 100;

	private final ChatThreadSummaryService chatThreadSummaryService;

	public ChatThreadSummaryController(ChatThreadSummaryService chatThreadSummaryService) {
		this.chatThreadSummaryService = chatThreadSummaryService;
	}

	/**
	 * 【列表查询】获取当前用户的会话汇总列表。
	 *
	 * <p>【端点】GET /api/chat/threads/summaries</p>
	 *
	 * <p>【查询参数】
	 * - limit: 返回数量（默认20，最大100）
	 * - offset: 偏移量（用于分页，默认0）</p>
	 *
	 * <p>【响应结构】
	 * 返回数据分为 pinnedThreads（置顶列表）和 normalThreads（普通列表），
	 * 置顶列表按 pinnedAt 降序，普通列表按 updatedAt 降序。</p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * GET /api/chat/threads/summaries?limit=20&offset=0
	 * </pre></p>
	 *
	 * @param limit  分页大小（可选）
	 * @param offset 偏移量（可选）
	 * @param principal 当前用户身份
	 * @return 会话汇总列表响应
	 */
	@GetMapping
	public ResponseEntity<ChatThreadSummaryListResponse> listSummaries(
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset,
			Principal principal) {

		// 【步骤1】身份认证 - 使用 Pattern Matching
		AuthenticatedUserContext user = extractAuthenticatedUser(principal);
		logger.debug("查询会话列表, userId={}, limit={}, offset={}", user.userId(), limit, offset);

		// 【步骤2】参数规范化
		int normalizedLimit = normalizeLimit(limit);
		int normalizedOffset = offset != null && offset > 0 ? offset : 0;

		// 【步骤3】调用服务层查询
		ChatThreadSummaryListData listData = chatThreadSummaryService.listSummaries(
				user.userId(), normalizedLimit, normalizedOffset);

		// 【步骤4】构建响应
		return ResponseEntity.ok(ChatThreadSummaryListResponse.ok(listData));
	}

	/**
	 * 【单条查询】获取指定会话的汇总信息。
	 *
	 * <p>【端点】GET /api/chat/threads/summaries/{threadId}</p>
	 *
	 * <p>【路径参数】
	 * - threadId: 会话唯一标识</p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * GET /api/chat/threads/summaries/thread_abc123
	 * </pre></p>
	 *
	 * @param threadId  会话ID
	 * @param principal 当前用户身份
	 * @return 会话汇总详情
	 */
	@GetMapping("/{threadId}")
	public ResponseEntity<ChatThreadSummaryViewData> getSummary(
			@PathVariable String threadId,
			Principal principal) {

		// 身份认证
		AuthenticatedUserContext user = extractAuthenticatedUser(principal);
		logger.debug("查询会话详情, userId={}, threadId={}", user.userId(), threadId);

		// 参数校验
		if (!StringUtils.hasText(threadId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID不能为空");
		}

		// 查询数据
		Optional<ChatThreadSummaryViewData> summaryOpt = chatThreadSummaryService
				.getSummary(user.userId(), threadId);

		// 处理结果 - 使用 Java 17 Optional Pattern
		return summaryOpt
				.map(ResponseEntity::ok)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "会话不存在或无权访问"));
	}

	/**
	 * 【部分更新】修改会话标题。
	 *
	 * <p>【端点】PATCH /api/chat/threads/summaries/{threadId}</p>
	 *
	 * <p>【为什么选择 PATCH 而非 PUT】
	 * - PATCH 表示部分更新，只修改标题字段，不影响其他字段
	 * - PUT 通常用于完整替换资源，不符合本场景</p>
	 *
	 * <p>【请求体】
	 * <pre>
	 * {
	 *   "title": "新的会话标题"
	 * }
	 * </pre></p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * PATCH /api/chat/threads/summaries/thread_abc123
	 * Content-Type: application/json
	 *
	 * {"title": "订单查询会话"}
	 * </pre></p>
	 *
	 * @param threadId 会话ID
	 * @param request  标题更新请求（手动校验）
	 * @param principal 当前用户身份
	 * @return 操作结果
	 */
	@PatchMapping("/{threadId}")
	public ResponseEntity<ChatThreadOperationResponse> updateTitle(
			@PathVariable String threadId,
			@RequestBody ChatThreadTitleUpdateRequest request,
			Principal principal) {

		// 手动校验
		String error = request.validate();
		if (error != null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
		}

		// 身份认证
		AuthenticatedUserContext user = extractAuthenticatedUser(principal);
		logger.info("修改会话标题, userId={}, threadId={}, newTitle={}",
				user.userId(), threadId, request.title());

		// 参数校验
		if (!StringUtils.hasText(threadId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID不能为空");
		}

		// 执行更新
		Optional<String> updatedTitle = chatThreadSummaryService
				.updateTitle(user.userId(), threadId, request.title());

		// 构建响应
		return updatedTitle
				.map(title -> ResponseEntity.ok(ChatThreadOperationResponse.ok(
						threadId, title, null, null, LocalDateTime.now())))
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "会话不存在或无权访问"));
	}

	/**
	 * 【置顶操作】置顶或取消置顶会话。
	 *
	 * <p>【端点】PUT /api/chat/threads/summaries/{threadId}/pin</p>
	 *
	 * <p>【为什么选择 PUT】
	 * - PUT 表示幂等操作，多次执行结果相同
	 * - 置顶状态是确定的（true/false），符合幂等性</p>
	 *
	 * <p>【请求体】
	 * <pre>
	 * {
	 *   "pinned": true   // 置顶
	 * }
	 * 或
	 * {
	 *   "pinned": false  // 取消置顶
	 * }
	 * </pre></p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * PUT /api/chat/threads/summaries/thread_abc123/pin
	 * Content-Type: application/json
	 *
	 * {"pinned": true}
	 * </pre></p>
	 *
	 * @param threadId 会话ID
	 * @param request  置顶请求
	 * @param principal 当前用户身份
	 * @return 操作结果
	 */
	@PutMapping("/{threadId}/pin")
	public ResponseEntity<ChatThreadOperationResponse> updatePinStatus(
			@PathVariable String threadId,
			@RequestBody ChatThreadPinRequest request,
			Principal principal) {

		// 手动校验
		String error = request.validate();
		if (error != null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
		}

		// 身份认证
		AuthenticatedUserContext user = extractAuthenticatedUser(principal);
		logger.info("更新置顶状态, userId={}, threadId={}, pinned={}",
				user.userId(), threadId, request.pinned());

		// 参数校验
		if (!StringUtils.hasText(threadId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID不能为空");
		}

		// 执行更新
		Optional<LocalDateTime> pinnedAt = chatThreadSummaryService
				.updatePinStatus(user.userId(), threadId, request.pinned());

		// 构建响应
		if (pinnedAt.isPresent() || !request.pinned()) {
			// 置顶成功 或 取消置顶成功
			return ResponseEntity.ok(ChatThreadOperationResponse.ok(
					threadId, null, request.pinned(), pinnedAt.orElse(null), LocalDateTime.now()));
		} else {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
		}
	}

	/**
	 * 【快捷置顶】快速置顶会话（无需请求体）。
	 *
	 * <p>【端点】PUT /api/chat/threads/summaries/{threadId}/pin/quick</p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * PUT /api/chat/threads/summaries/thread_abc123/pin/quick
	 * </pre></p>
	 *
	 * @param threadId 会话ID
	 * @param principal 当前用户身份
	 * @return 操作结果
	 */
	@PutMapping("/{threadId}/pin/quick")
	public ResponseEntity<ChatThreadOperationResponse> pinQuick(
			@PathVariable String threadId,
			Principal principal) {

		return updatePinStatus(threadId, ChatThreadPinRequest.pin(), principal);
	}

	/**
	 * 【删除单条】删除指定会话。
	 *
	 * <p>【端点】DELETE /api/chat/threads/summaries/{threadId}</p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * DELETE /api/chat/threads/summaries/thread_abc123
	 * </pre></p>
	 *
	 * @param threadId 会话ID
	 * @param principal 当前用户身份
	 * @return 204 No Content 删除成功
	 */
	@DeleteMapping("/{threadId}")
	public ResponseEntity<BatchDeleteResponse> deleteThread(
			@PathVariable String threadId,
			Principal principal) {

		// 身份认证
		AuthenticatedUserContext user = extractAuthenticatedUser(principal);
		logger.info("删除会话, userId={}, threadId={}", user.userId(), threadId);

		// 参数校验
		if (!StringUtils.hasText(threadId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID不能为空");
		}

		// 执行删除
		boolean success = chatThreadSummaryService.deleteThread(user.userId(), threadId);

		if (success) {
			return ResponseEntity.ok(
					new BatchDeleteResponse(
							0, "success", 1, 0)
			);
		} else {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
		}
	}

	/**
	 * 【批量删除】删除多个会话。
	 *
	 * <p>【端点】DELETE /api/chat/threads/summaries</p>
	 *
	 * <p>【请求体】
	 * <pre>
	 * {
	 *   "threadIds": ["thread_abc", "thread_def", "thread_ghi"]
	 * }
	 * </pre></p>
	 *
	 * <p>【调用示例】
	 * <pre>
	 * DELETE /api/chat/threads/summaries
	 * Content-Type: application/json
	 *
	 * {"threadIds": ["thread_abc123", "thread_def456"]}
	 * </pre></p>
	 *
	 * @param request   批量删除请求
	 * @param principal 当前用户身份
	 * @return 删除结果统计
	 */
	@DeleteMapping
	public ResponseEntity<BatchDeleteResponse> batchDeleteThreads(
			@RequestBody BatchDeleteRequest request,
			Principal principal) {

		// 手动校验
		if (request == null || request.threadIds() == null || request.threadIds().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID列表不能为空");
		}

		// 身份认证
		AuthenticatedUserContext user = extractAuthenticatedUser(principal);
		logger.info("批量删除会话, userId={}, threadIds={}", user.userId(), request.threadIds());

		// 执行批量删除
		int deletedCount = chatThreadSummaryService.batchDeleteThreads(user.userId(), request.threadIds());

		// 构建响应
		return ResponseEntity.ok(new BatchDeleteResponse(
				0, "success", deletedCount, request.threadIds().size() - deletedCount));
	}

	// ==================== 私有工具方法 ====================

	/**
	 * 从 Principal 提取认证用户上下文 - Java 17 Pattern Matching。
	 *
	 * @param principal Spring Security Principal
	 * @return 认证用户上下文
	 * @throws ResponseStatusException 401 如果未认证
	 */
	private AuthenticatedUserContext extractAuthenticatedUser(Principal principal) {
		// Java 17 Pattern Matching for instanceof
		if (principal instanceof Authentication authentication
				&& authentication.getPrincipal() instanceof AuthenticatedUserContext userContext
				&& StringUtils.hasText(userContext.userId())) {
			return userContext;
		}
		logger.warn("身份认证失败: principal={}", principal);
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证或会话已过期");
	}

	/**
	 * 规范化分页参数。
	 *
	 * @param limit 原始分页参数
	 * @return 规范化后的值（1-100）
	 */
	private int normalizeLimit(Integer limit) {
		if (limit == null || limit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limit, MAX_LIMIT);
	}

	// ==================== 内部请求/响应 Record ====================

	/**
	 * 批量删除请求。
	 *
	 * @param threadIds 会话ID列表（至少一个）
	 */
	public record BatchDeleteRequest(List<String> threadIds) {
	}

	/**
	 * 批量删除响应。
	 *
	 * @param code          响应码
	 * @param msg           响应消息
	 * @param deletedCount  成功删除数量
	 * @param failedCount   失败数量
	 */
	public record BatchDeleteResponse(int code, String msg, int deletedCount, int failedCount) {
	}
}
