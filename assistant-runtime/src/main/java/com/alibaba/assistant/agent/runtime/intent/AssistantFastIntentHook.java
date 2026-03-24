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
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceArtifact;
import com.alibaba.assistant.agent.runtime.agent.ConversationUserInputResolver;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
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

	private static final String[] ACTION_DIRECTIVE_HINT_KEYWORDS = new String[] {
			"写", "填写", "上报", "记录", "预约", "预定", "预订", "安排", "办理", "创建", "新建", "提交",
			"发起", "申请"
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
	private final ArtifactPublicationLookupService artifactPublicationLookupService;

	private final boolean forceDisableStreaming;

	public AssistantFastIntentHook(AssistantIntentRouter intentRouter, ObjectMapper objectMapper) {
		this(intentRouter, objectMapper, false, null);
	}

	@Autowired
	public AssistantFastIntentHook(
			AssistantIntentRouter intentRouter,
			ObjectMapper objectMapper,
			@Value("${assistant.runtime.fast-intent.force-disable-streaming:false}")
			boolean forceDisableStreaming) {
		this(intentRouter, objectMapper, forceDisableStreaming, null);
	}

	public AssistantFastIntentHook(
			AssistantIntentRouter intentRouter,
			ObjectMapper objectMapper,
			boolean forceDisableStreaming,
			@Nullable ArtifactPublicationLookupService artifactPublicationLookupService) {
		this.intentRouter = intentRouter;
		this.objectMapper = objectMapper;
		this.forceDisableStreaming = forceDisableStreaming;
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
			String input = state != null
					? firstNonBlank(
							state.value(AssistantStateKeys.CURRENT_TURN_USER_INPUT, String.class).orElse(null),
							state.value("input", String.class).orElse(null))
					: null;
			Map<String, Object> confirmationUpdates = tryBuildConfirmationExecutionUpdates(state, input);
			if (!confirmationUpdates.isEmpty()) {
				return CompletableFuture.completedFuture(confirmationUpdates);
			}

			List<Message> messages = resolveMessages(state);
			String resolvedInput = resolveInput(input, state, messages);
			Map<String, Object> collectionContinuationUpdates = tryBuildCollectionContinuationUpdates(state, resolvedInput);
			if (!collectionContinuationUpdates.isEmpty()) {
				return CompletableFuture.completedFuture(collectionContinuationUpdates);
			}
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

			AssistantMessage assistantMessage = AssistantMessage.builder()
					.content("")
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
					.content("")
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

	private Map<String, Object> tryBuildCollectionContinuationUpdates(OverAllState state, String input) {
		if (state == null) {
			return Map.of();
		}
		boolean hasStructuredSlotInputs = hasExplicitCurrentTurnSlotInputs(state);
		if (!StringUtils.hasText(input) && !hasStructuredSlotInputs) {
			return Map.of();
		}
		String phase = state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null);
		if (!StringUtils.hasText(phase)) {
			return Map.of();
		}
		String normalizedPhase = phase.trim().toUpperCase(Locale.ROOT);
		if (!"COLLECTING".equals(normalizedPhase) && !"BLOCKED".equals(normalizedPhase)) {
			return Map.of();
		}
		if (!hasStructuredSlotInputs && !hasNewUserInputForCollection(state, input)) {
			logger.debug("AssistantFastIntentHook#tryBuildCollectionContinuationUpdates - skip duplicate collect input");
			return Map.of();
		}
		if (!isAllowedByNameWhitelist(state, SLOT_COLLECT_TOOL)) {
			logger.debug("AssistantFastIntentHook#tryBuildCollectionContinuationUpdates - slot_collect blocked by allowlist");
			return Map.of();
		}
		String matchedToolCode = resolveMatchedToolCode(state);
		if (!StringUtils.hasText(matchedToolCode)) {
			return Map.of();
		}
		Map<String, Object> matchedSnapshot = resolveMatchedToolMetaSnapshot(state, matchedToolCode);
		String routeType = hasStructuredSlotInputs
				? "COLLECTION_CONTINUE_STRUCTURED"
				: "COLLECTION_CONTINUE";
		return buildDirectSlotCollectUpdates(
				matchedToolCode,
				matchedSnapshot,
				routeType);
	}


	private Map<String, Object> tryBuildOperationCollectUpdates(OverAllState state, String input) {
		if (!StringUtils.hasText(input)) {
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

				return buildFormExtractionUpdates(
				matchedTool.toolCode(),
				matchedTool.snapshot(),
				"OPERATION_COLLECT");
	}

	private Map<String, Object> buildDirectSlotCollectUpdates(
			String matchedToolCode,
			Map<String, Object> matchedSnapshot,
			String routeType) {
		if (!StringUtils.hasText(matchedToolCode)) {
			return Map.of();
		}
		try {
			String toolCallId = "assistant_continue_collect_" + UUID.randomUUID().toString().substring(0, 8);
			AssistantMessage assistantMessage = AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall(
							toolCallId,
							"function",
							SLOT_COLLECT_TOOL,
							toJson(Map.of("toolCode", matchedToolCode)))))
					.build();

			Map<String, Object> fastIntentState = new LinkedHashMap<>();
			fastIntentState.put("hit", true);
			fastIntentState.put("route_type", routeType);
			fastIntentState.put("tool_code", matchedToolCode);

			Map<String, Object> updates = new LinkedHashMap<>();
			updates.put("messages", List.of(assistantMessage));
			updates.put("jump_to", JumpTo.tool);
			updates.put("fast_intent", fastIntentState);
			updates.put("current_date", LocalDate.now().toString());
			updates.put(AssistantStateKeys.FORM_FLOW_EXTRACTION_PENDING, Boolean.FALSE);
			if (matchedSnapshot != null && !matchedSnapshot.isEmpty()) {
				updates.put(AssistantStateKeys.MATCHED_TOOL_META, matchedSnapshot);
			}
			return updates;
		}
		catch (JsonProcessingException ex) {
			logger.warn("AssistantFastIntentHook#buildDirectSlotCollectUpdates - build failed, error={}", ex.getMessage());
			return Map.of();
		}
	}

	private Map<String, Object> buildFormExtractionUpdates(
			String matchedToolCode,
			Map<String, Object> matchedSnapshot,
			String routeType) {
		if (!StringUtils.hasText(matchedToolCode)) {
			return Map.of();
		}
		Map<String, Object> fastIntentState = new LinkedHashMap<>();
		fastIntentState.put("hit", true);
		fastIntentState.put("route_type", routeType);
		fastIntentState.put("tool_code", matchedToolCode);

		Map<String, Object> updates = new LinkedHashMap<>();
		updates.put("jump_to", JumpTo.model);
		updates.put("fast_intent", fastIntentState);
		updates.put("current_date", LocalDate.now().toString());
		updates.put(AssistantStateKeys.FORM_FLOW_EXTRACTION_PENDING, Boolean.TRUE);
		if (matchedSnapshot != null && !matchedSnapshot.isEmpty()) {
			updates.put(AssistantStateKeys.MATCHED_TOOL_META, matchedSnapshot);
		}
		return updates;
	}

	private Map<String, Object> resolveMatchedToolMetaSnapshot(OverAllState state, String matchedToolCode) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		Object raw = state != null ? state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null) : null;
		if (raw instanceof Map<?, ?> rawMap) {
			rawMap.forEach((key, value) -> {
				if (key != null) {
					snapshot.put(String.valueOf(key), value);
				}
			});
		}
		else if (raw != null) {
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> converted = objectMapper.convertValue(raw, Map.class);
				if (converted != null) {
					snapshot.putAll(converted);
				}
			}
			catch (IllegalArgumentException ignored) {
				// Ignore and fallback to toolCode only.
			}
		}
		if (!snapshot.containsKey("toolCode") && StringUtils.hasText(matchedToolCode)) {
			snapshot.put("toolCode", matchedToolCode);
		}
		return snapshot;
	}
	private OperationTarget resolveBestOperationTarget(OverAllState state, String input) {
		if (!StringUtils.hasText(input)) {
			return null;
		}
		return resolveBestPublishedOperationTool(state, input);
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
				if (!isTopLevelOperationCandidate(candidate)) {
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


	private int scorePublishedArtifact(PublishedToolDescriptor descriptor, String normalizedInput) {
		if (descriptor == null || descriptor.artifact() == null || !StringUtils.hasText(normalizedInput)) {
			return 0;
		}
		if (!isTopLevelOperationCandidate(descriptor)) {
			return 0;
		}
		boolean hasStrongActionHint = containsAny(normalizedInput, STRONG_OPERATION_HINT_KEYWORDS);
		boolean hasQueryHint = containsAny(normalizedInput, QUERY_HINT_KEYWORDS);
		if (hasQueryHint && !hasStrongActionHint) {
			return 0;
		}
		boolean hasDirectiveSignal = containsAny(normalizedInput, ACTION_DIRECTIVE_HINT_KEYWORDS)
				|| containsAny(normalizedInput, OPERATION_HINT_KEYWORDS);
		int descriptorTermScore = scoreDescriptorTermMatches(descriptor, normalizedInput);
		boolean hasDirectNameMatch = hasDirectDisplayNameMatch(descriptor, normalizedInput);
		if (!hasDirectiveSignal && !hasStrongActionHint && !hasDirectNameMatch) {
			return 0;
		}
		String searchableText = normalizeForMatch(String.join(" ", List.of(
				firstNonBlank(resolvePublishedDisplayName(descriptor), ""),
				firstNonBlank(resolvePublishedDescription(descriptor), ""),
				firstNonBlank(descriptor.artifact().getArtifactCode(), ""))));
		int score = isLikelyWriteArtifact(descriptor) ? 2 : 0;
		score += descriptorTermScore;
		score += scoreKeywordMatches(normalizedInput, searchableText, STRONG_OPERATION_HINT_KEYWORDS, 3);
		score += scoreKeywordMatches(normalizedInput, searchableText, OPERATION_HINT_KEYWORDS, 1);
		if (descriptorTermScore > 0 && hasDirectiveSignal) {
			score += 1;
		}
		String artifactCode = normalizeForMatch(descriptor.artifact().getArtifactCode());
		for (String token : artifactCode.split("[._-]")) {
			if (!StringUtils.hasText(token) || token.length() < 3 || GENERIC_MATCH_TOKENS.contains(token)) {
				continue;
			}
			if (normalizedInput.contains(token)) {
				score += 2;
			}
		}
		return score;
	}

	private int scoreKeywordMatches(String normalizedInput, String searchableText, String[] keywords, int weight) {
		if (!StringUtils.hasText(normalizedInput) || !StringUtils.hasText(searchableText)
				|| keywords == null || keywords.length == 0 || weight <= 0) {
			return 0;
		}
		Set<String> matchedKeywords = new HashSet<>();
		int score = 0;
		for (String keyword : keywords) {
			String normalizedKeyword = normalizeForMatch(keyword);
			if (!StringUtils.hasText(normalizedKeyword)) {
				continue;
			}
			if (normalizedInput.contains(normalizedKeyword)
					&& searchableText.contains(normalizedKeyword)
					&& matchedKeywords.add(normalizedKeyword)) {
				score += weight;
			}
		}
		return score;
	}

	private boolean isTopLevelOperationCandidate(PublishedToolDescriptor descriptor) {
		return descriptor != null
				&& descriptor.artifact() != null
				&& descriptor.isUserVisible()
				&& hasPublishedSlotSchema(descriptor)
				&& !"QUERY".equalsIgnoreCase(descriptor.toolType())
				&& !isLikelyQueryArtifact(descriptor);
	}

	private boolean hasPublishedSlotSchema(PublishedToolDescriptor descriptor) {
		RuntimeArtifact.Interaction interaction = descriptor != null && descriptor.artifact() != null
				? descriptor.artifact().getInteraction()
				: null;
		return interaction != null && StringUtils.hasText(interaction.slotSchemaJson());
	}
	private int scoreDescriptorTermMatches(PublishedToolDescriptor descriptor, String normalizedInput) {
		if (!StringUtils.hasText(normalizedInput)) {
			return 0;
		}
		Set<String> matchedTerms = new LinkedHashSet<>();
		int score = 0;
		for (String term : extractDescriptorTerms(descriptor)) {
			if (!normalizedInput.contains(term) || !matchedTerms.add(term)) {
				continue;
			}
			score += term.length() >= 4 ? 3 : 2;
		}
		return Math.min(score, 6);
	}

	private boolean hasDirectDisplayNameMatch(PublishedToolDescriptor descriptor, String normalizedInput) {
		String displayName = normalizeForMatch(resolvePublishedDisplayName(descriptor));
		if (!StringUtils.hasText(displayName) || !StringUtils.hasText(normalizedInput)) {
			return false;
		}
		return normalizedInput.contains(displayName);
	}

	private Set<String> extractDescriptorTerms(PublishedToolDescriptor descriptor) {
		Set<String> terms = new LinkedHashSet<>();
		addDescriptorTerms(terms, resolvePublishedDisplayName(descriptor), true);
		if (descriptor != null && descriptor.artifact() != null) {
			addDescriptorTerms(terms, descriptor.artifact().getArtifactCode(), false);
		}
		return terms;
	}

	private void addDescriptorTerms(Set<String> terms, String rawText, boolean allowCjkFragments) {
		String normalized = normalizeForMatch(rawText);
		if (!StringUtils.hasText(normalized)) {
			return;
		}
		for (String token : normalized.split("[\\s,，。；;：:/()（）\\[\\]{}<>|]+")) {
			addDescriptorToken(terms, token, allowCjkFragments);
			for (String nested : token.split("[._-]")) {
				addDescriptorToken(terms, nested, allowCjkFragments);
			}
		}
	}

	private void addDescriptorToken(Set<String> terms, String rawToken, boolean allowCjkFragments) {
		String token = normalizeForMatch(rawToken);
		if (!StringUtils.hasText(token) || GENERIC_MATCH_TOKENS.contains(token)) {
			return;
		}
		if (token.length() >= 3 && isAsciiAlphaNumeric(token)) {
			terms.add(token);
			return;
		}
		if (!containsCjk(token)) {
			return;
		}
		if (token.length() >= 2 && token.length() <= 8) {
			terms.add(token);
		}
		if (!allowCjkFragments || token.length() < 4 || token.length() > 8) {
			return;
		}
		for (int size = 2; size <= Math.min(4, token.length()); size++) {
			for (int index = 0; index <= token.length() - size; index++) {
				String fragment = token.substring(index, index + size);
				if (!GENERIC_MATCH_TOKENS.contains(fragment)) {
					terms.add(fragment);
				}
			}
		}
	}

	private boolean containsCjk(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
				return true;
			}
		}
		return false;
	}

	private boolean isAsciiAlphaNumeric(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			char current = text.charAt(i);
			if ((current < 'a' || current > 'z') && (current < '0' || current > '9')) {
				return false;
			}
		}
		return true;
	}

	private boolean isLikelyQueryArtifact(PublishedToolDescriptor descriptor) {
		String text = normalizeForMatch(firstNonBlank(
				resolvePublishedDisplayName(descriptor),
				resolvePublishedDescription(descriptor),
				descriptor != null && descriptor.artifact() != null ? descriptor.artifact().getArtifactCode() : null));
		return containsAny(text, "query", "search", "list", "read", "lookup", "查询", "检索", "列表", "统计", "获取");
	}

	private boolean isLikelyWriteArtifact(PublishedToolDescriptor descriptor) {
		if (descriptor == null || descriptor.artifact() == null) {
			return false;
		}
		if ("QUERY".equalsIgnoreCase(descriptor.toolType())) {
			return false;
		}
		if (descriptor.artifact().getArtifactType() == RuntimeArtifact.ArtifactType.ACTION
				|| descriptor.artifact().getArtifactType() == RuntimeArtifact.ArtifactType.WORKFLOW) {
			return true;
		}
		String text = normalizeForMatch(firstNonBlank(
				resolvePublishedDisplayName(descriptor),
				resolvePublishedDescription(descriptor),
				descriptor.artifact().getArtifactCode()));
		return containsAny(text, "create", "update", "delete", "submit", "approve", "apply", "发起", "申请", "提交",
				"修改", "删除", "审批", "报销", "请假", "预约", "预订", "预定", "汇报", "记录");
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
			snapshot.put("behaviorConfig", interaction.askStrategyJson());
			snapshot.put("requiresConfirm", requiresPublishedConfirmation(artifact, interaction, publishedRiskLevel));
		}
		else {
			snapshot.put("slotSchema", null);
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
		return ConversationUserInputResolver.resolve(input, state, messages);
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
		return StringUtils.hasText(toolCode) ? "artifact_execute" : null;
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
		if (hasExplicitCurrentTurnSlotInputs(state)) {
			return true;
		}
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

	private boolean hasExplicitCurrentTurnSlotInputs(OverAllState state) {
		if (state == null) {
			return false;
		}
		Object raw = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
		if (!(raw instanceof Map<?, ?> slotInputs) || slotInputs.isEmpty()) {
			return false;
		}
		for (Map.Entry<?, ?> entry : slotInputs.entrySet()) {
			String key = entry.getKey() != null ? String.valueOf(entry.getKey()).trim() : null;
			if (!StringUtils.hasText(key) || isIgnoredCurrentTurnInputKey(key)) {
				continue;
			}
			if (hasMeaningfulStructuredValue(entry.getValue())) {
				return true;
			}
		}
		return false;
	}

	private boolean isIgnoredCurrentTurnInputKey(String key) {
		return AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS.equals(key)
				|| AssistantStateKeys.THREAD_ID.equals(key)
				|| AssistantStateKeys.ASSISTANT_UID.equals(key)
				|| AssistantStateKeys.SYSTEM_CODE.equals(key)
				|| AssistantStateKeys.AGENT_APP_CODE.equals(key)
				|| AssistantStateKeys.ROLE_PACKAGE_CODE.equals(key)
				|| AssistantStateKeys.ROLE_PACKAGE_VERSION.equals(key)
				|| AssistantStateKeys.ROLE_SCENARIO_CODE.equals(key)
				|| AssistantStateKeys.SPACE_ID.equals(key)
				|| AssistantStateKeys.SPACE_CODE.equals(key)
				|| AssistantStateKeys.SPACE_ENVIRONMENT.equals(key)
				|| CodeactStateKeys.AVAILABLE_TOOL_NAMES.equals(key)
				|| "input".equalsIgnoreCase(key)
				|| "query".equalsIgnoreCase(key)
				|| "messages".equalsIgnoreCase(key)
				|| "threadId".equals(key)
				|| "assistantUid".equals(key)
				|| "systemCode".equals(key)
				|| "agentAppCode".equals(key)
				|| "userId".equals(key)
				|| "user_id".equalsIgnoreCase(key);
	}

	private boolean hasMeaningfulStructuredValue(Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof String text) {
			return StringUtils.hasText(text);
		}
		if (value instanceof Map<?, ?> map) {
			return !map.isEmpty();
		}
		if (value instanceof List<?> list) {
			return !list.isEmpty();
		}
		return true;
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


	private record OperationTarget(String toolCode, Map<String, Object> snapshot) {
	}

	private record ScoredOperationTarget(OperationTarget target, int score) {
	}

}



