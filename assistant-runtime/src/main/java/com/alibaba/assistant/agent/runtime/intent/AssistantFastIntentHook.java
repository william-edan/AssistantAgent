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
package com.alibaba.assistant.agent.runtime.intent;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceArtifact;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.LegacyCompatibilityLogHelper;
import com.alibaba.assistant.agent.runtime.registry.PublicationScopeResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.runtime.registry.ToolPublicationProvider;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Prioritized;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Migration runtime fast-intent hook based on {@link AssistantIntentRouter}.
 *
 * <p>When matched, it jumps to tool execution directly and skips the model call for this round.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class AssistantFastIntentHook extends AgentHook implements Prioritized {

	private static final Logger logger = LoggerFactory.getLogger(AssistantFastIntentHook.class);

	private static final Set<String> CONFIRMING_PHASES = Set.of("CONFIRMING", "READY_TO_CONFIRM");

	private static final String SLOT_COLLECT_TOOL = "slot_collect";
	private static final String STREAM_METADATA_KEY = "_stream_";

	private static final String DEFAULT_TENANT = "default";

	private static final int OPERATION_MIN_SCORE = 3;

	private static final int OPERATION_MIN_MARGIN = 2;

	private static final String[] OPERATION_HINT_KEYWORDS = new String[] {
			"申请", "发起", "提交", "创建", "新增", "修改", "更新", "删除", "撤销", "审批", "报销", "请假",
			"休假", "调休", "离职", "入职", "转正", "借款", "付款", "execute", "create", "update", "delete",
			"submit", "approve", "apply"
	};

	private static final String[] STRONG_OPERATION_HINT_KEYWORDS = new String[] {
			"需要请假", "想请假", "我要请假", "请假一天", "请假半天", "申请", "发起", "提交", "创建",
			"新增", "修改", "更新", "删除", "撤销", "审批", "报销"
	};

	private static final String[] QUERY_HINT_KEYWORDS = new String[] {
			"查询", "查一下", "查下", "查看", "看看", "获取", "搜索", "检索", "统计", "多少", "几", "什么",
			"如何", "怎么", "why", "what", "how", "where", "when", "query", "search"
	};

	private static final Set<String> GENERIC_MATCH_TOKENS = Set.of(
			"系统", "用户", "信息", "数据", "操作", "流程", "接口", "服务", "管理", "功能",
			"提交", "创建", "执行", "处理", "查询", "获取", "查看", "列表", "单据", "业务",
			"tool", "meta", "system", "service", "request", "execute", "operation", "query", "data", "info");

	private static final String[] POSITIVE_CONFIRM_KEYWORDS = new String[] {
			"确认", "确定", "同意", "可以", "好的", "没问题", "提交", "发起", "执行", "继续",
			"yes", "ok", "okay", "confirm", "approved", "approve", "y", "1"
	};

	private static final String[] NEGATIVE_CONFIRM_KEYWORDS = new String[] {
			"取消", "不用", "暂不", "不要", "拒绝", "不同意", "不确认", "不提交", "否", "no", "not now"
	};

	private final AssistantIntentRouter intentRouter;

	private final ObjectMapper objectMapper;

	@Nullable
	private final ToolMetaService toolMetaService;

	@Nullable
	private final PublicationScopeResolver publicationScopeResolver;

	@Nullable
	private final ArtifactPublicationLookupService artifactPublicationLookupService;

	private final boolean forceDisableStreaming;

	public AssistantFastIntentHook(AssistantIntentRouter intentRouter, ObjectMapper objectMapper) {
		this(intentRouter, objectMapper, null, false, null, null);
	}

	public AssistantFastIntentHook(
			AssistantIntentRouter intentRouter,
			ObjectMapper objectMapper,
			@Nullable ToolMetaService toolMetaService) {
		this(intentRouter, objectMapper, toolMetaService, false, null, null);
	}

	public AssistantFastIntentHook(
			AssistantIntentRouter intentRouter,
			ObjectMapper objectMapper,
			@Nullable ToolMetaService toolMetaService,
			boolean forceDisableStreaming) {
		this(intentRouter, objectMapper, toolMetaService, forceDisableStreaming, null, null);
	}

	public AssistantFastIntentHook(
			AssistantIntentRouter intentRouter,
			ObjectMapper objectMapper,
			@Nullable ToolMetaService toolMetaService,
			boolean forceDisableStreaming,
			@Nullable PublicationScopeResolver publicationScopeResolver) {
		this(intentRouter, objectMapper, toolMetaService, forceDisableStreaming, publicationScopeResolver, null);
	}

	@Autowired
	public AssistantFastIntentHook(
			AssistantIntentRouter intentRouter,
			ObjectMapper objectMapper,
			@Nullable ToolMetaService toolMetaService,
			@Value("${assistant.runtime.fast-intent.force-disable-streaming:false}")
			boolean forceDisableStreaming,
			@Nullable PublicationScopeResolver publicationScopeResolver,
			@Nullable ArtifactPublicationLookupService artifactPublicationLookupService) {
		this.intentRouter = intentRouter;
		this.objectMapper = objectMapper;
		this.toolMetaService = toolMetaService;
		this.forceDisableStreaming = forceDisableStreaming;
		this.publicationScopeResolver = publicationScopeResolver;
		this.artifactPublicationLookupService = artifactPublicationLookupService;
	}

	@Override
	public String getName() {
		return "AssistantFastIntentHook";
	}

	@Override
	public int getOrder() {
		return HookPriorityConstants.FAST_INTENT_HOOK;
	}

	@Override
	public List<JumpTo> canJumpTo() {
		return List.of(JumpTo.tool, JumpTo.model);
	}

	@Override
	public Map<String, KeyStrategy> getKeyStrategys() {
		// Keep jump_to as a single latest enum value; avoid stale append that can cause tool/model loop.
		return Map.of("jump_to", new ReplaceStrategy());
	}

	@Override
	public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
		applyStreamingOverride(config);
		try {
			String input = state != null ? state.value("input", String.class).orElse(null) : null;
			Map<String, Object> confirmationUpdates = tryBuildConfirmationExecutionUpdates(state, input);
			if (!confirmationUpdates.isEmpty()) {
				return CompletableFuture.completedFuture(confirmationUpdates);
			}

			List<Message> messages = resolveMessages(state);
			String resolvedInput = resolveInput(input, state, messages);
			Map<String, Object> operationCollectUpdates = tryBuildOperationCollectUpdates(state, resolvedInput);
			if (!operationCollectUpdates.isEmpty()) {
				return CompletableFuture.completedFuture(operationCollectUpdates);
			}

			Map<String, Object> metadata = config != null
					? config.metadata().orElse(Collections.emptyMap())
					: Collections.emptyMap();
			AssistantIntentRouter.IntentResult route = intentRouter.route(resolvedInput, state, metadata);
			if (route.type() != AssistantIntentType.FAST_INTENT) {
				return CompletableFuture.completedFuture(resetStaleJumpTo(state));
			}

			Experience experience = route.matchedExperience().orElse(null);
			if (experience == null) {
				return CompletableFuture.completedFuture(resetStaleJumpTo(state));
			}

			List<ExperienceArtifact.ToolCallSpec> callSpecs = extractToolCalls(experience);
			if (callSpecs.isEmpty()) {
				return CompletableFuture.completedFuture(resetStaleJumpTo(state));
			}

			List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
			for (ExperienceArtifact.ToolCallSpec callSpec : callSpecs) {
				if (callSpec == null || !StringUtils.hasText(callSpec.getToolName())) {
					continue;
				}
				if (!isAllowedByNameWhitelist(state, callSpec.getToolName())) {
					logger.warn("AssistantFastIntentHook#beforeAgent - reason=tool blocked by allowlist, toolName={}",
							callSpec.getToolName());
					return CompletableFuture.completedFuture(Map.of());
				}
				String toolCallId = "assistant_fast_intent_" + UUID.randomUUID().toString().substring(0, 8);
				toolCalls.add(new AssistantMessage.ToolCall(
						toolCallId,
						"function",
						callSpec.getToolName(),
						toJson(callSpec.getArguments())));
			}
			if (toolCalls.isEmpty()) {
				return CompletableFuture.completedFuture(resetStaleJumpTo(state));
			}

			String assistantText = experience.getArtifact() != null && experience.getArtifact().getReact() != null
					? experience.getArtifact().getReact().getAssistantText()
					: null;
			AssistantMessage assistantMessage = AssistantMessage.builder()
					.content(assistantText)
					.toolCalls(toolCalls)
					.build();

			Map<String, Object> fastIntentState = new LinkedHashMap<>();
			fastIntentState.put("hit", true);
			fastIntentState.put("experience_id", experience.getId());
			fastIntentState.put("experience_title", experience.getTitle());
			fastIntentState.put("experience_type",
					experience.getType() != null ? experience.getType().name() : null);
			fastIntentState.put("route_type", route.type().name());

			Map<String, Object> updates = new LinkedHashMap<>();
			updates.put("messages", List.of(assistantMessage));
			updates.put("jump_to", JumpTo.tool);
			updates.put("fast_intent", fastIntentState);
			return CompletableFuture.completedFuture(updates);
		}
		catch (Exception ex) {
			logger.warn("AssistantFastIntentHook#beforeAgent - reason=hook execution failed, error={}", ex.getMessage());
			return CompletableFuture.completedFuture(resetStaleJumpTo(state));
		}
	}

	private void applyStreamingOverride(@Nullable RunnableConfig config) {
		if (!forceDisableStreaming || config == null) {
			return;
		}
		try {
			Map<String, Object> metadata = config.metadata().orElse(null);
			if (metadata == null) {
				return;
			}
			if (!Boolean.FALSE.equals(metadata.get(STREAM_METADATA_KEY))) {
				metadata.put(STREAM_METADATA_KEY, false);
			}
		}
		catch (Exception ex) {
			logger.debug("AssistantFastIntentHook#applyStreamingOverride - skip due to error={}", ex.getMessage());
		}
	}

	private Map<String, Object> resetStaleJumpTo(OverAllState state) {
		if (!hasStaleToolJump(state)) {
			return Map.of();
		}
		Map<String, Object> updates = new LinkedHashMap<>();
		// Clear stale jump flag instead of forcing JumpTo.model.
		// Forcing JumpTo.model can pin the graph into repeated model turns.
		updates.put("jump_to", null);
		return updates;
	}

	private boolean hasStaleToolJump(OverAllState state) {
		if (state == null) {
			return false;
		}
		Object jumpToRaw = state.value("jump_to", Object.class).orElse(null);
		if (jumpToRaw == null) {
			return false;
		}
		if (jumpToRaw instanceof JumpTo jumpTo) {
			return jumpTo == JumpTo.tool;
		}
		if (jumpToRaw instanceof Enum<?> enumValue) {
			return "tool".equalsIgnoreCase(enumValue.name());
		}
		String text = asText(jumpToRaw);
		return StringUtils.hasText(text) && "tool".equalsIgnoreCase(text);
	}

	private Map<String, Object> tryBuildConfirmationExecutionUpdates(OverAllState state, String input) {
		if (state == null) {
			return Map.of();
		}
		String phase = state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null);
		if (!StringUtils.hasText(phase) || !CONFIRMING_PHASES.contains(phase.trim().toUpperCase(Locale.ROOT))) {
			return Map.of();
		}

		List<Message> messages = resolveMessages(state);
		String resolvedInput = resolveInput(input, state, messages);
		if (!hasNewUserInputForConfirmation(state, resolvedInput)) {
			return Map.of();
		}
		if (!isUserConfirmed(resolvedInput)) {
			return Map.of();
		}

		String matchedToolCode = resolveMatchedToolCode(state);
		if (!StringUtils.hasText(matchedToolCode)) {
			logger.warn("AssistantFastIntentHook#tryBuildConfirmationExecutionUpdates - missing matched tool code");
			return Map.of();
		}

		List<String> allowlist = resolveAllowlist(state);
		String executeToolName = resolveExecuteToolName(state, matchedToolCode, allowlist);
		if (!StringUtils.hasText(executeToolName)) {
			logger.warn(
					"AssistantFastIntentHook#tryBuildConfirmationExecutionUpdates - failed to resolve execute tool name, toolCode={}",
					matchedToolCode);
			return Map.of();
		}
		boolean allowlistExplicitlyConfigured = isAllowlistExplicitlyConfigured(state);
		boolean allowlistHasExecuteTool = containsIgnoreCase(allowlist, executeToolName);
		boolean canAutoGrantExecuteTool = canAutoGrantConfirmationExecuteTool(state, executeToolName);
		if (allowlistExplicitlyConfigured && allowlist.isEmpty()) {
			logger.warn(
					"AssistantFastIntentHook#tryBuildConfirmationExecutionUpdates - execute tool blocked by empty allowlist, toolName={}",
					executeToolName);
			return Map.of();
		}
		if (allowlistExplicitlyConfigured && !allowlistHasExecuteTool && !canAutoGrantExecuteTool) {
			logger.warn(
					"AssistantFastIntentHook#tryBuildConfirmationExecutionUpdates - execute tool blocked by allowlist, toolName={}",
					executeToolName);
			return Map.of();
		}

		Map<String, Object> executeArgs = buildExecuteArgs(executeToolName, matchedToolCode, state);

		try {
			String toolCallId = "assistant_confirm_exec_" + UUID.randomUUID().toString().substring(0, 8);
			AssistantMessage assistantMessage = AssistantMessage.builder()
					.content("用户已确认，执行操作。")
					.toolCalls(List.of(new AssistantMessage.ToolCall(
							toolCallId,
							"function",
							executeToolName,
							toJson(executeArgs))))
					.build();

			Map<String, Object> updates = new LinkedHashMap<>();
			updates.put("messages", List.of(assistantMessage));
			updates.put("jump_to", JumpTo.tool);
			updates.put(AssistantStateKeys.EXECUTION_CONFIRM_GRANTED, true);
			updates.put(AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME, executeToolName);
			updates.put(AssistantStateKeys.EXECUTION_CONFIRM_USER_INPUT, resolvedInput);
			if (allowlistExplicitlyConfigured && !allowlist.isEmpty() && !allowlistHasExecuteTool) {
				List<String> mergedAllowlist = new ArrayList<>(allowlist);
				mergedAllowlist.add(executeToolName);
				updates.put(CodeactStateKeys.AVAILABLE_TOOL_NAMES, mergedAllowlist);
			}
			return updates;
		}
		catch (Exception e) {
			logger.warn("AssistantFastIntentHook#tryBuildConfirmationExecutionUpdates - build failed, error={}",
					e.getMessage());
			return Map.of();
		}
	}

	private Map<String, Object> tryBuildOperationCollectUpdates(OverAllState state, String input) {
		if (!StringUtils.hasText(input) || !isLikelyOperationIntent(input)) {
			return Map.of();
		}
		if (!hasNewUserInputForCollection(state, input)) {
			logger.debug(
					"AssistantFastIntentHook#tryBuildOperationCollectUpdates - skip duplicate collect in same collecting turn");
			return Map.of();
		}
		if (!isAllowedByNameWhitelist(state, SLOT_COLLECT_TOOL)) {
			logger.debug("AssistantFastIntentHook#tryBuildOperationCollectUpdates - slot_collect blocked by allowlist");
			return Map.of();
		}

		OperationTarget matchedTool = resolveBestOperationTarget(state, input);
		if (matchedTool == null || !StringUtils.hasText(matchedTool.toolCode())) {
			return Map.of();
		}

		try {
			Map<String, Object> collectArgs = new LinkedHashMap<>();
			collectArgs.put("toolCode", matchedTool.toolCode());
			collectArgs.put("extractedSlots", Collections.emptyMap());

			String toolCallId = "assistant_op_collect_" + UUID.randomUUID().toString().substring(0, 8);
			AssistantMessage assistantMessage = AssistantMessage.builder()
					.content("识别为操作请求，开始槽位收集。")
					.toolCalls(List.of(new AssistantMessage.ToolCall(
							toolCallId,
							"function",
							SLOT_COLLECT_TOOL,
							toJson(collectArgs))))
					.build();

			Map<String, Object> fastIntentState = new LinkedHashMap<>();
			fastIntentState.put("hit", true);
			fastIntentState.put("route_type", "OPERATION_COLLECT");
			fastIntentState.put("tool_code", matchedTool.toolCode());

			Map<String, Object> updates = new LinkedHashMap<>();
			updates.put("messages", List.of(assistantMessage));
			updates.put("jump_to", JumpTo.tool);
			updates.put("fast_intent", fastIntentState);
			updates.put("current_date", LocalDate.now().toString());
			updates.put(AssistantStateKeys.MATCHED_TOOL_META, matchedTool.snapshot());
			return updates;
		}
		catch (Exception e) {
			logger.warn("AssistantFastIntentHook#tryBuildOperationCollectUpdates - build failed, error={}",
					e.getMessage());
			return Map.of();
		}
	}

	private boolean isLikelyOperationIntent(String input) {
		String normalized = normalizeForMatch(input);
		if (!StringUtils.hasText(normalized)) {
			return false;
		}
		boolean hasOperationHint = containsAny(normalized, OPERATION_HINT_KEYWORDS);
		if (!hasOperationHint) {
			return false;
		}
		boolean hasQueryHint = containsAny(normalized, QUERY_HINT_KEYWORDS);
		if (hasQueryHint && !containsAny(normalized, STRONG_OPERATION_HINT_KEYWORDS)) {
			return false;
		}
		return true;
	}

	private OperationTarget resolveBestOperationTarget(OverAllState state, String input) {
		if (!StringUtils.hasText(input)) {
			return null;
		}
		ToolPublicationProvider.PublicationScope scope = resolvePublicationScope(state);
		if (shouldTryArtifactOperationRouting(scope)) {
			OperationTarget publishedTarget = resolveBestPublishedOperationTool(state, input);
			if (publishedTarget != null) {
				return publishedTarget;
			}
			if (!allowsLegacyOperationFallback(scope)) {
				return null;
			}
		}
		ToolMeta matchedTool = resolveBestLegacyOperationTool(state, input);
		if (matchedTool == null || !StringUtils.hasText(matchedTool.getToolCode())) {
			return null;
		}
		LegacyCompatibilityLogHelper.logFallback(
				logger,
				"AssistantFastIntentHook#resolveBestOperationTarget",
				"legacy tool",
				scope,
				resolveTenantId(state),
				matchedTool.getToolCode());
		return new OperationTarget(matchedTool.getToolCode(), buildMatchedToolMetaSnapshot(matchedTool));
	}

	@Nullable
	private ToolPublicationProvider.PublicationScope resolvePublicationScope(OverAllState state) {
		if (publicationScopeResolver == null || state == null) {
			return null;
		}
		return publicationScopeResolver.resolve(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
	}

	private boolean shouldTryArtifactOperationRouting(@Nullable ToolPublicationProvider.PublicationScope scope) {
		return scope != null && containsIgnoreCase(scope.requestedSourceIds(), "artifact-catalog");
	}

	private boolean allowsLegacyOperationFallback(@Nullable ToolPublicationProvider.PublicationScope scope) {
		if (scope == null) {
			return true;
		}
		if (containsIgnoreCase(scope.requestedSourceIds(), "legacy-bridge")) {
			return true;
		}
		if (containsIgnoreCase(scope.blockedSourceIds(), "legacy-bridge")) {
			return false;
		}
		return scope.sourceSelectionMode() != ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE;
	}

	private OperationTarget resolveBestPublishedOperationTool(OverAllState state, String input) {
		if (artifactPublicationLookupService == null || state == null || !StringUtils.hasText(input)) {
			return null;
		}
		try {
			ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
			List<PublishedToolDescriptor> candidates = artifactPublicationLookupService.listPublishedArtifacts(toolContext);
			if (candidates == null || candidates.isEmpty()) {
				return null;
			}
			String normalizedInput = normalizeForMatch(input);
			String systemCode = resolveSystemCode(state);
			ScoredOperationTarget best = null;
			ScoredOperationTarget second = null;
			for (PublishedToolDescriptor candidate : candidates) {
				if (candidate == null || candidate.artifact() == null
						|| !StringUtils.hasText(candidate.artifact().getArtifactCode())) {
					continue;
				}
				if (StringUtils.hasText(systemCode)
						&& StringUtils.hasText(candidate.executionSystemCode())
						&& !systemCode.equalsIgnoreCase(candidate.executionSystemCode())) {
					continue;
				}
				int score = scorePublishedArtifact(candidate, normalizedInput);
				if (score <= 0) {
					continue;
				}
				OperationTarget target = new OperationTarget(
						candidate.artifact().getArtifactCode(),
						buildMatchedToolMetaSnapshot(candidate));
				ScoredOperationTarget scored = new ScoredOperationTarget(target, score);
				if (best == null || score > best.score()) {
					second = best;
					best = scored;
				}
				else if (second == null || score > second.score()) {
					second = scored;
				}
			}
			if (best == null || best.score() < OPERATION_MIN_SCORE) {
				return null;
			}
			if (second != null && (best.score() - second.score()) < OPERATION_MIN_MARGIN) {
				logger.info(
						"AssistantFastIntentHook#resolveBestPublishedOperationTool - ambiguous match, best={}, second={}, bestScore={}, secondScore={}",
						best.target().toolCode(),
						second.target().toolCode(),
						best.score(),
						second.score());
				return null;
			}
			logger.info("AssistantFastIntentHook#resolveBestPublishedOperationTool - matched artifactCode={}, score={}",
					best.target().toolCode(), best.score());
			return best.target();
		}
		catch (Exception e) {
			logger.warn("AssistantFastIntentHook#resolveBestPublishedOperationTool - failed, error={}", e.getMessage());
			return null;
		}
	}

	private ToolMeta resolveBestLegacyOperationTool(OverAllState state, String input) {
		if (toolMetaService == null || !StringUtils.hasText(input)) {
			return null;
		}
		try {
			String tenantId = resolveTenantId(state);
			String systemCode = resolveSystemCode(state);
			List<ToolMeta> candidates = toolMetaService.listEnabledByTenantAndSystem(tenantId, systemCode);
			if (candidates == null || candidates.isEmpty()) {
				return null;
			}
			String normalizedInput = normalizeForMatch(input);
			ScoredTool best = null;
			ScoredTool second = null;
			for (ToolMeta candidate : candidates) {
				if (candidate == null || !StringUtils.hasText(candidate.getToolCode())) {
					continue;
				}
				int score = scoreToolMeta(candidate, normalizedInput);
				if (score <= 0) {
					continue;
				}
				ScoredTool scored = new ScoredTool(candidate, score);
				if (best == null || score > best.score()) {
					second = best;
					best = scored;
				}
				else if (second == null || score > second.score()) {
					second = scored;
				}
			}
			if (best == null || best.score() < OPERATION_MIN_SCORE) {
				return null;
			}
			if (second != null && (best.score() - second.score()) < OPERATION_MIN_MARGIN) {
				logger.info(
						"AssistantFastIntentHook#resolveBestLegacyOperationTool - ambiguous match, best={}, second={}, bestScore={}, secondScore={}",
						best.tool().getToolCode(),
						second.tool().getToolCode(),
						best.score(),
						second.score());
				return null;
			}
			logger.info("AssistantFastIntentHook#resolveBestLegacyOperationTool - matched toolCode={}, score={}",
					best.tool().getToolCode(), best.score());
			return best.tool();
		}
		catch (Exception e) {
			logger.warn("AssistantFastIntentHook#resolveBestLegacyOperationTool - failed, error={}", e.getMessage());
			return null;
		}
	}

	private int scoreToolMeta(ToolMeta meta, String normalizedInput) {
		int score = 0;
		boolean strongOperationIntent = containsAny(normalizedInput, STRONG_OPERATION_HINT_KEYWORDS);
		boolean queryIntent = containsAny(normalizedInput, QUERY_HINT_KEYWORDS);
		score += scoreTextMatch(normalizedInput, meta.getToolName(), 6, 2);
		score += scoreTextMatch(normalizedInput, meta.getDescription(), 4, 1);
		score += scoreTextMatch(normalizedInput, meta.getToolCode(), 4, 1);
		if (strongOperationIntent && isLikelyWriteTool(meta)) {
			score += 3;
		}
		if (strongOperationIntent && isLikelyQueryTool(meta)) {
			score -= 3;
		}
		if (queryIntent && !strongOperationIntent && isLikelyQueryTool(meta)) {
			score += 2;
		}
		if (isLikelyQueryTool(meta) && !containsAny(normalizedInput, STRONG_OPERATION_HINT_KEYWORDS)) {
			score -= 2;
		}
		if (isLikelyWriteTool(meta)) {
			score += 1;
		}
		return Math.max(score, 0);
	}

	private int scorePublishedArtifact(PublishedToolDescriptor descriptor, String normalizedInput) {
		int score = 0;
		boolean strongOperationIntent = containsAny(normalizedInput, STRONG_OPERATION_HINT_KEYWORDS);
		boolean queryIntent = containsAny(normalizedInput, QUERY_HINT_KEYWORDS);
		score += scoreTextMatch(normalizedInput, resolvePublishedDisplayName(descriptor), 6, 2);
		score += scoreTextMatch(normalizedInput, resolvePublishedDescription(descriptor), 4, 1);
		score += scoreTextMatch(normalizedInput,
				descriptor != null && descriptor.artifact() != null ? descriptor.artifact().getArtifactCode() : null,
				4,
				1);
		if (strongOperationIntent && isLikelyWriteArtifact(descriptor)) {
			score += 3;
		}
		if (strongOperationIntent && isLikelyQueryArtifact(descriptor)) {
			score -= 3;
		}
		if (queryIntent && !strongOperationIntent && isLikelyQueryArtifact(descriptor)) {
			score += 2;
		}
		if (isLikelyQueryArtifact(descriptor) && !containsAny(normalizedInput, STRONG_OPERATION_HINT_KEYWORDS)) {
			score -= 2;
		}
		if (isLikelyWriteArtifact(descriptor)) {
			score += 1;
		}
		return Math.max(score, 0);
	}

	private int scoreTextMatch(String normalizedInput, String sourceText, int fullTextWeight, int tokenWeight) {
		String candidate = normalizeForMatch(sourceText);
		if (!StringUtils.hasText(candidate) || !StringUtils.hasText(normalizedInput)) {
			return 0;
		}

		int score = 0;
		if (candidate.length() >= 2 && normalizedInput.contains(candidate)) {
			score += fullTextWeight;
		}

		Set<String> tokens = tokenizeForMatch(candidate);
		for (String token : tokens) {
			if (normalizedInput.contains(token)) {
				score += token.length() >= 3 ? (tokenWeight + 1) : tokenWeight;
			}
		}
		return score;
	}

	private Set<String> tokenizeForMatch(String text) {
		if (!StringUtils.hasText(text)) {
			return Set.of();
		}
		Set<String> tokens = new HashSet<>();
		String normalized = normalizeForMatch(text);
		String[] pieces = normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+");
		for (String piece : pieces) {
			if (!StringUtils.hasText(piece)) {
				continue;
			}
			if (isChineseText(piece)) {
				addChineseTokens(piece, tokens);
			}
			else if (piece.length() >= 3 && !GENERIC_MATCH_TOKENS.contains(piece)) {
				tokens.add(piece);
			}
		}
		return tokens;
	}

	private boolean isChineseText(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < '\u4e00' || c > '\u9fa5') {
				return false;
			}
		}
		return StringUtils.hasText(text);
	}

	private void addChineseTokens(String text, Set<String> output) {
		if (!StringUtils.hasText(text) || output == null) {
			return;
		}
		String normalized = text.trim();
		if (normalized.length() >= 2
				&& normalized.length() <= 8
				&& !GENERIC_MATCH_TOKENS.contains(normalized)) {
			output.add(normalized);
		}
		for (int i = 0; i < normalized.length() - 1; i++) {
			String bi = normalized.substring(i, i + 2);
			if (!GENERIC_MATCH_TOKENS.contains(bi)) {
				output.add(bi);
			}
		}
		for (int i = 0; i < normalized.length() - 2; i++) {
			String tri = normalized.substring(i, i + 3);
			if (!GENERIC_MATCH_TOKENS.contains(tri)) {
				output.add(tri);
			}
		}
	}

	private boolean isLikelyQueryTool(ToolMeta meta) {
		String text = normalizeForMatch(firstNonBlank(meta.getCapabilityType(), meta.getToolName(), meta.getDescription()));
		return containsAny(text, "query", "search", "list", "read", "lookup", "查询", "检索", "列表", "统计", "获取");
	}

	private boolean isLikelyWriteTool(ToolMeta meta) {
		String text = normalizeForMatch(firstNonBlank(meta.getCapabilityType(), meta.getToolName(), meta.getDescription()));
		return containsAny(text, "create", "update", "delete", "submit", "approve", "apply", "发起", "申请", "提交",
				"修改", "删除", "审批", "报销", "请假");
	}

	private boolean isLikelyQueryArtifact(PublishedToolDescriptor descriptor) {
		String text = normalizeForMatch(firstNonBlank(
				resolvePublishedDisplayName(descriptor),
				resolvePublishedDescription(descriptor),
				descriptor != null && descriptor.artifact() != null ? descriptor.artifact().getArtifactCode() : null));
		return containsAny(text, "query", "search", "list", "read", "lookup", "查询", "检索", "列表", "统计", "获取");
	}

	private boolean isLikelyWriteArtifact(PublishedToolDescriptor descriptor) {
		String text = normalizeForMatch(firstNonBlank(
				resolvePublishedDisplayName(descriptor),
				resolvePublishedDescription(descriptor),
				descriptor != null && descriptor.artifact() != null ? descriptor.artifact().getArtifactCode() : null));
		return containsAny(text, "create", "update", "delete", "submit", "approve", "apply", "发起", "申请", "提交",
				"修改", "删除", "审批", "报销", "请假");
	}

	private String resolvePublishedDisplayName(PublishedToolDescriptor descriptor) {
		if (descriptor == null) {
			return null;
		}
		return firstNonBlank(
				descriptor.displayName(),
				descriptor.artifact() != null ? descriptor.artifact().getDisplayName() : null,
				descriptor.artifact() != null ? descriptor.artifact().getArtifactCode() : null);
	}

	private String resolvePublishedDescription(PublishedToolDescriptor descriptor) {
		if (descriptor == null) {
			return null;
		}
		return firstNonBlank(
				descriptor.targetClassDescription(),
				descriptor.targetClassName(),
				descriptor.publicationKey());
	}

	private String resolveTenantId(OverAllState state) {
		String tenantId = firstNonBlank(readStateString(state, "tenant_id"), readStateString(state, "tenantId"));
		return StringUtils.hasText(tenantId) ? tenantId : DEFAULT_TENANT;
	}

	private String resolveSystemCode(OverAllState state) {
		return firstNonBlank(
				readStateString(state, AssistantStateKeys.SYSTEM_CODE),
				readStateString(state, "system_code"),
				readStateString(state, "systemCode"));
	}

	private String readStateString(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object value = state.value(key).orElse(null);
		return asText(value);
	}

	private Map<String, Object> buildMatchedToolMetaSnapshot(ToolMeta tool) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("toolCode", tool.getToolCode());
		snapshot.put("toolName", tool.getToolName());
		snapshot.put("description", tool.getDescription());
		snapshot.put("systemCode", tool.getSystemCode());
		snapshot.put("slotSchema", tool.getParameterSchema());
		snapshot.put("requestSchema", tool.getParameterSchema());
		snapshot.put("executionPlan", tool.getExecutionPlan());
		snapshot.put("behaviorConfig", tool.getInteractionPolicy());
		snapshot.put("riskLevel", tool.getRiskLevel());
		snapshot.put("requiresConfirm", tool.getRequiresConfirm());
		return snapshot;
	}

	private Map<String, Object> buildMatchedToolMetaSnapshot(PublishedToolDescriptor descriptor) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		RuntimeArtifact artifact = descriptor != null ? descriptor.artifact() : null;
		RuntimeArtifact.Interaction interaction = artifact != null ? artifact.getInteraction() : null;
		String publishedRiskLevel = resolvePublishedRiskLevel(descriptor);
		String toolCode = artifact != null ? artifact.getArtifactCode() : null;
		snapshot.put("toolCode", toolCode);
		snapshot.put("toolName", resolvePublishedDisplayName(descriptor));
		snapshot.put("description", resolvePublishedDescription(descriptor));
		snapshot.put("systemCode", descriptor != null ? descriptor.executionSystemCode() : null);
		if (interaction != null) {
			snapshot.put("slotSchema", interaction.slotSchemaJson());
			snapshot.put("requestSchema", null);
			snapshot.put("behaviorConfig", interaction.askStrategyJson());
			snapshot.put("requiresConfirm", requiresPublishedConfirmation(artifact, interaction, publishedRiskLevel));
		}
		else {
			snapshot.put("slotSchema", null);
			snapshot.put("requestSchema", null);
			snapshot.put("behaviorConfig", null);
			snapshot.put("requiresConfirm", requiresPublishedConfirmation(artifact, null, publishedRiskLevel));
		}
		snapshot.put("executionPlan", null);
		snapshot.put("riskLevel", publishedRiskLevel);
		return snapshot;
	}

	private String resolvePublishedRiskLevel(PublishedToolDescriptor descriptor) {
		RuntimeArtifact artifact = descriptor != null ? descriptor.artifact() : null;
		if (artifact == null || artifact.getActions() == null || artifact.getActions().isEmpty()) {
			return null;
		}
		String highest = null;
		for (RuntimeArtifact.ActionBinding action : artifact.getActions().values()) {
			String candidate = action != null ? normalizeRiskLevel(action.riskLevel()) : null;
			if (riskPriority(candidate) > riskPriority(highest)) {
				highest = candidate;
			}
		}
		return highest;
	}

	private boolean requiresPublishedConfirmation(RuntimeArtifact artifact,
			RuntimeArtifact.Interaction interaction,
			String publishedRiskLevel) {
		if (interaction != null && StringUtils.hasText(interaction.confirmationPolicyJson())) {
			return true;
		}
		if (artifact != null && artifact.getActions() != null) {
			for (RuntimeArtifact.ActionBinding action : artifact.getActions().values()) {
				if (action != null && action.approvalPolicyId() != null) {
					return true;
				}
			}
		}
		return riskPriority(publishedRiskLevel) >= 2;
	}

	private String normalizeRiskLevel(String riskLevel) {
		if (!StringUtils.hasText(riskLevel)) {
			return null;
		}
		return riskLevel.trim().toUpperCase(Locale.ROOT);
	}

	private int riskPriority(String riskLevel) {
		String normalized = normalizeRiskLevel(riskLevel);
		if (normalized == null) {
			return 0;
		}
		return switch (normalized) {
			case "LOW" -> 1;
			case "MEDIUM" -> 2;
			case "HIGH" -> 3;
			case "CRITICAL" -> 4;
			default -> 0;
		};
	}

	private boolean containsAny(String source, String... keywords) {
		if (!StringUtils.hasText(source) || keywords == null || keywords.length == 0) {
			return false;
		}
		for (String keyword : keywords) {
			if (StringUtils.hasText(keyword) && source.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	private String normalizeForMatch(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		return text.trim().toLowerCase(Locale.ROOT);
	}

	private List<ExperienceArtifact.ToolCallSpec> extractToolCalls(Experience experience) {
		if (experience == null
				|| experience.getArtifact() == null
				|| experience.getArtifact().getReact() == null
				|| experience.getArtifact().getReact().getPlan() == null) {
			return List.of();
		}
		List<ExperienceArtifact.ToolCallSpec> specs = experience.getArtifact().getReact().getPlan().getToolCalls();
		return specs != null ? specs : List.of();
	}

	@SuppressWarnings("unchecked")
	private List<Message> resolveMessages(OverAllState state) {
		if (state == null) {
			return List.of();
		}
		return state.value("messages", List.class).orElse(List.of());
	}

	private String resolveInput(String input, OverAllState state, List<Message> messages) {
		if (StringUtils.hasText(input)) {
			return input;
		}
		if (state != null) {
			String stateInput = state.value("input", String.class).orElse(null);
			if (StringUtils.hasText(stateInput)) {
				return stateInput;
			}
		}
		if (messages == null || messages.isEmpty()) {
			return null;
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message message = messages.get(i);
			if (message instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
				return userMessage.getText();
			}
		}
		return null;
	}

	private boolean isUserConfirmed(String input) {
		if (!StringUtils.hasText(input)) {
			return false;
		}
		String normalized = input.trim().toLowerCase(Locale.ROOT);
		for (String keyword : NEGATIVE_CONFIRM_KEYWORDS) {
			if (normalized.contains(keyword)) {
				return false;
			}
		}
		for (String keyword : POSITIVE_CONFIRM_KEYWORDS) {
			if (normalized.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	private String resolveMatchedToolCode(OverAllState state) {
		if (state == null) {
			return null;
		}
		Object raw = state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null);
		if (raw == null) {
			return null;
		}
		String direct = asText(readMapValue(asMap(raw), "toolCode", "tool_code"));
		if (StringUtils.hasText(direct)) {
			return direct;
		}
		if (raw instanceof String jsonText && StringUtils.hasText(jsonText)) {
			try {
				Map<String, Object> map = objectMapper.readValue(jsonText, new TypeReference<Map<String, Object>>() {
				});
				return asText(readMapValue(map, "toolCode", "tool_code"));
			}
			catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}

	private String resolveExecuteToolName(OverAllState state, String toolCode, List<String> allowlist) {
		ToolPublicationProvider.PublicationScope scope = resolveConfirmationPublicationScope(state);
		if (shouldPreferArtifactExecute(state, toolCode, scope)) {
			return "artifact_execute";
		}
		maybeLogLegacyExecuteFallback(state, toolCode, scope);
		String candidate = normalizeIdentifier(toolCode) + "_execute";
		if (allowlist == null || allowlist.isEmpty()) {
			return candidate;
		}
		String exact = findExecuteToolFromAllowlist(allowlist, candidate);
		if (StringUtils.hasText(exact)) {
			return exact;
		}

		String capabilityCandidate = buildCapabilityOnlyExecuteCandidate(toolCode);
		if (StringUtils.hasText(capabilityCandidate)) {
			String capabilityMatch = findExecuteToolFromAllowlist(allowlist, capabilityCandidate);
			if (StringUtils.hasText(capabilityMatch)) {
				return capabilityMatch;
			}
		}

		String singleExecute = findSingleExecuteTool(allowlist);
		if (StringUtils.hasText(singleExecute)) {
			return singleExecute;
		}
		return candidate;
	}

	private boolean isAllowlistExplicitlyConfigured(OverAllState state) {
		if (state == null) {
			return false;
		}
		return state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null) instanceof List<?>;
	}

	private boolean canAutoGrantConfirmationExecuteTool(OverAllState state, String executeToolName) {
		return isArtifactExecuteToolName(executeToolName);
	}

	private boolean isArtifactExecuteToolName(String toolName) {
		return StringUtils.hasText(toolName) && "artifact_execute".equalsIgnoreCase(toolName.trim());
	}

	private ToolPublicationProvider.PublicationScope resolveConfirmationPublicationScope(OverAllState state) {
		if (publicationScopeResolver == null || state == null) {
			return null;
		}
		return publicationScopeResolver.resolve(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
	}

	private boolean shouldPreferArtifactExecute(OverAllState state, String matchedToolCode,
			ToolPublicationProvider.PublicationScope scope) {
		if (artifactPublicationLookupService == null || state == null || !StringUtils.hasText(matchedToolCode)) {
			return false;
		}
		if (scope == null) {
			return false;
		}
		boolean artifactRequested = containsIgnoreCase(scope.requestedSourceIds(), "artifact-catalog");
		boolean legacyRequested = containsIgnoreCase(scope.requestedSourceIds(), "legacy-bridge");
		boolean legacyHidden = (scope.sourceSelectionMode() == ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE
				&& !legacyRequested)
				|| containsIgnoreCase(scope.blockedSourceIds(), "legacy-bridge");
		if (!artifactRequested || !legacyHidden) {
			return false;
		}
		ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
		return artifactPublicationLookupService.findPublishedArtifact(matchedToolCode, toolContext).isPresent();
	}

	private void maybeLogLegacyExecuteFallback(OverAllState state, String matchedToolCode,
			ToolPublicationProvider.PublicationScope scope) {
		if (artifactPublicationLookupService == null || state == null || !StringUtils.hasText(matchedToolCode)
				|| scope == null) {
			return;
		}
		boolean artifactRequested = containsIgnoreCase(scope.requestedSourceIds(), "artifact-catalog");
		boolean legacyRequested = containsIgnoreCase(scope.requestedSourceIds(), "legacy-bridge");
		boolean legacyHidden = (scope.sourceSelectionMode() == ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE
				&& !legacyRequested)
				|| containsIgnoreCase(scope.blockedSourceIds(), "legacy-bridge");
		if (!artifactRequested || !legacyHidden) {
			return;
		}
		ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
		if (artifactPublicationLookupService.findPublishedArtifact(matchedToolCode, toolContext).isPresent()) {
			return;
		}
		LegacyCompatibilityLogHelper.logFallback(
				logger,
				"AssistantFastIntentHook#resolveExecuteToolName",
				"legacy execute tool",
				scope,
				resolveTenantId(state),
				matchedToolCode);
	}

	private Map<String, Object> buildExecuteArgs(String executeToolName, String matchedToolCode, OverAllState state) {
		Map<String, Object> collectedSlots = resolveCollectedSlots(state);
		if (isArtifactExecuteToolName(executeToolName)) {
			Map<String, Object> executeArgs = new LinkedHashMap<>();
			executeArgs.put("toolCode", matchedToolCode);
			executeArgs.put("params", collectedSlots);
			executeArgs.put("confirmed", true);
			return executeArgs;
		}
		collectedSlots.put("confirmed", true);
		collectedSlots.putIfAbsent("confirm", true);
		return collectedSlots;
	}

	private String findExecuteToolFromAllowlist(List<String> allowlist, String candidate) {
		if (!StringUtils.hasText(candidate) || allowlist == null || allowlist.isEmpty()) {
			return null;
		}
		String normalizedCandidate = candidate.trim().toLowerCase(Locale.ROOT);
		for (String name : allowlist) {
			if (!StringUtils.hasText(name)) {
				continue;
			}
			String trimmed = name.trim();
			if (candidate.equalsIgnoreCase(trimmed)) {
				return trimmed;
			}
		}
		for (String name : allowlist) {
			if (!StringUtils.hasText(name)) {
				continue;
			}
			String trimmed = name.trim();
			String normalized = trimmed.toLowerCase(Locale.ROOT);
			if (normalized.startsWith(normalizedCandidate + "_")) {
				return trimmed;
			}
		}
		return null;
	}

	private String buildCapabilityOnlyExecuteCandidate(String toolCode) {
		if (!StringUtils.hasText(toolCode)) {
			return null;
		}
		String trimmed = toolCode.trim();
		int idx = trimmed.lastIndexOf('.');
		if (idx < 0 || idx >= trimmed.length() - 1) {
			return null;
		}
		String capabilityPart = trimmed.substring(idx + 1);
		if (!StringUtils.hasText(capabilityPart)) {
			return null;
		}
		return normalizeIdentifier(capabilityPart) + "_execute";
	}

	private String findSingleExecuteTool(List<String> allowlist) {
		if (allowlist == null || allowlist.isEmpty()) {
			return null;
		}
		String hit = null;
		for (String name : allowlist) {
			if (!StringUtils.hasText(name)) {
				continue;
			}
			String trimmed = name.trim();
			String normalized = trimmed.toLowerCase(Locale.ROOT);
			if (!normalized.endsWith("_execute") && !normalized.matches(".*_execute_[0-9]+$")) {
				continue;
			}
			if (hit != null && !hit.equalsIgnoreCase(trimmed)) {
				return null;
			}
			hit = trimmed;
		}
		return hit;
	}

	@SuppressWarnings("unchecked")
	private List<String> resolveAllowlist(OverAllState state) {
		if (state == null) {
			return List.of();
		}
		Object raw = state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null);
		if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
			return List.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (Object item : rawList) {
			if (item == null) {
				continue;
			}
			String text = String.valueOf(item).trim();
			if (StringUtils.hasText(text)) {
				names.add(text);
			}
		}
		return new ArrayList<>(names);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> resolveCollectedSlots(OverAllState state) {
		if (state == null) {
			return new LinkedHashMap<>();
		}
		Object raw = state.value(AssistantStateKeys.COLLECTED_SLOTS, Object.class).orElse(null);
		if (!(raw instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> collected = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
			String slotName = asText(entry.getKey());
			if (!StringUtils.hasText(slotName)) {
				continue;
			}
			Object resolved = resolveSlotValue(entry.getValue());
			if (resolved != null) {
				collected.put(slotName, resolved);
			}
		}
		return collected;
	}

	private Object resolveSlotValue(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof SlotValue slotValue) {
			return slotValue.getResolvedValue();
		}
		if (raw instanceof Map<?, ?> rawMap) {
			Map<String, Object> map = objectMapper.convertValue(rawMap, new TypeReference<Map<String, Object>>() {
			});
			Object candidate = firstNonNull(
					map.get("resolvedValue"),
					map.get("resolved_value"),
					map.get("value"),
					map.get("rawValue"),
					map.get("raw_value"));
			return candidate != null ? candidate : map;
		}
		return raw;
	}

	private Map<String, Object> asMap(Object raw) {
		if (raw == null) {
			return Map.of();
		}
		if (raw instanceof Map<?, ?> rawMap) {
			return objectMapper.convertValue(rawMap, new TypeReference<Map<String, Object>>() {
			});
		}
		try {
			return objectMapper.convertValue(raw, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private Object readMapValue(Map<String, Object> map, String... keys) {
		if (map == null || map.isEmpty() || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			if (map.containsKey(key)) {
				return map.get(key);
			}
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				if (entry.getKey() != null && key.equalsIgnoreCase(entry.getKey())) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private boolean containsIgnoreCase(List<String> source, String target) {
		if (source == null || source.isEmpty() || !StringUtils.hasText(target)) {
			return false;
		}
		for (String item : source) {
			if (item != null && target.equalsIgnoreCase(item.trim())) {
				return true;
			}
		}
		return false;
	}

	private boolean hasNewUserInputForConfirmation(OverAllState state, String resolvedInput) {
		if (!StringUtils.hasText(resolvedInput)) {
			return false;
		}
		String lastCollectInput = state != null
				? state.value(AssistantStateKeys.LAST_COLLECT_USER_INPUT, String.class).orElse(null)
				: null;
		if (!StringUtils.hasText(lastCollectInput)) {
			return true;
		}
		return !resolvedInput.trim().equalsIgnoreCase(lastCollectInput.trim());
	}

	private boolean hasNewUserInputForCollection(OverAllState state, String resolvedInput) {
		if (!StringUtils.hasText(resolvedInput)) {
			return false;
		}
		if (state == null) {
			return true;
		}
		String phase = state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null);
		if (!StringUtils.hasText(phase)) {
			return true;
		}
		String normalizedPhase = phase.trim().toUpperCase(Locale.ROOT);
		if (!"COLLECTING".equals(normalizedPhase) && !"BLOCKED".equals(normalizedPhase)) {
			return true;
		}
		String lastCollectInput = state.value(AssistantStateKeys.LAST_COLLECT_USER_INPUT, String.class).orElse(null);
		if (!StringUtils.hasText(lastCollectInput)) {
			return true;
		}
		return !resolvedInput.trim().equalsIgnoreCase(lastCollectInput.trim());
	}

	private Object firstNonNull(Object... values) {
		if (values == null || values.length == 0) {
			return null;
		}
		for (Object value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private String normalizeIdentifier(String raw) {
		if (!StringUtils.hasText(raw)) {
			return "capability";
		}
		String normalized = raw.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9_]+", "_")
				.replaceAll("_+", "_")
				.replaceAll("^_", "")
				.replaceAll("_$", "");
		if (!StringUtils.hasText(normalized)) {
			normalized = "capability";
		}
		if (Character.isDigit(normalized.charAt(0))) {
			normalized = "tool_" + normalized;
		}
		return normalized;
	}

	@SuppressWarnings("unchecked")
	private boolean isAllowedByNameWhitelist(OverAllState state, String toolName) {
		if (state == null || !StringUtils.hasText(toolName)) {
			return true;
		}
		Object raw = state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null);
		if (!(raw instanceof List<?> allowlist)) {
			return true;
		}
		if (allowlist.isEmpty()) {
			return false;
		}
		for (Object item : allowlist) {
			if (item != null && toolName.equalsIgnoreCase(String.valueOf(item))) {
				return true;
			}
		}
		return false;
	}

	private String toJson(Map<String, Object> arguments) throws JsonProcessingException {
		if (arguments == null || arguments.isEmpty()) {
			return "{}";
		}
		return objectMapper.writeValueAsString(arguments);
	}

	private record ScoredTool(ToolMeta tool, int score) {
	}

	private record OperationTarget(String toolCode, Map<String, Object> snapshot) {
	}

	private record ScoredOperationTarget(OperationTarget target, int score) {
	}

}
