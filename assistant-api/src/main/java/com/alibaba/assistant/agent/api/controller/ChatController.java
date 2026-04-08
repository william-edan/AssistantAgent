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

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendMessageVisibilitySupport;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadStateData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
import com.alibaba.assistant.agent.api.service.ChatTranscriptPersistenceService;
import com.alibaba.assistant.agent.api.service.ChatFrontendEventPublisher;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventType;
import com.alibaba.assistant.agent.runtime.execution.ExecutionLifecycleStatus;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventStreamRegistry;
import com.alibaba.assistant.agent.runtime.context.RuntimeSpaceResolver;
import com.alibaba.assistant.agent.runtime.role.ScenarioRouter;
import com.alibaba.cloud.ai.agent.studio.dto.AgentResumeRequest;
import com.alibaba.cloud.ai.agent.studio.dto.AgentRunRequest;
import com.alibaba.cloud.ai.agent.studio.dto.messages.MessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolRequestMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolResponseMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolRequestConfirmMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.UserMessageDTO;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.UUID;

/**
 * 聊天主入口控制器。
 *
 * <p>负责对外提供 {@code run_sse} 和 {@code resume_sse} 两条写接口，
 * 把用户输入送入 Agent 运行时，再把内部事件转换成前端可消费的结构化 SSE 协议。
 * 这一层同时负责线程级事件发布、流式去重和聊天记录持久化主流程编排。</p>
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat")
public class ChatController {

	private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
	private static final Set<String> RUN_REPLAY_STATE_KEYS = Set.of(
			AssistantStateKeys.THREAD_ID,
			AssistantStateKeys.ASSISTANT_UID,
			AssistantStateKeys.SYSTEM_CODE,
			AssistantStateKeys.AGENT_APP_CODE,
			AssistantStateKeys.ROLE_PACKAGE_CODE,
			AssistantStateKeys.ROLE_PACKAGE_VERSION,
			AssistantStateKeys.ROLE_SCENARIO_CODE,
			AssistantStateKeys.SPACE_ID,
			AssistantStateKeys.SPACE_CODE,
			AssistantStateKeys.SPACE_ENVIRONMENT,
			AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS);

	private final AgentLoader agentLoader;

	private final ObjectMapper mapper = new ObjectMapper();

	private final String defaultAppName;

	private final String defaultSystemCode;

	private final String defaultSpaceCode;

	private final String defaultSpaceEnvironment;

	private final ExecutionEventStreamRegistry executionEventStreamRegistry;

	private final V3ProtocolAdapter protocolAdapter;

	private final ChatTranscriptPersistenceService transcriptPersistenceService;

	private final ChatThreadStateService chatThreadStateService;

	private final ChatFrontendEventPublisher chatFrontendEventPublisher;

	private final RuntimeSpaceResolver runtimeSpaceResolver;

	private final ScenarioRouter scenarioRouter;

	public ChatController(AgentLoader agentLoader,
			@Value("${assistant.chat.default-app-name:grayscale_agent}") String defaultAppName,
			@Value("${assistant.chat.default-system-code:}")
			String defaultSystemCode) {
		this(agentLoader, defaultAppName, defaultSystemCode, "", "prod", null, null, null, null, null, null);
	}

	public ChatController(
			AgentLoader agentLoader,
			String defaultAppName,
			String defaultSystemCode,
			String defaultSpaceCode,
			String defaultSpaceEnvironment) {
		this(agentLoader, defaultAppName, defaultSystemCode, defaultSpaceCode, defaultSpaceEnvironment, null, null, null, null, null, null);
	}

	public ChatController(
			AgentLoader agentLoader,
			String defaultAppName,
			String defaultSystemCode,
			@Nullable ExecutionEventStreamRegistry executionEventStreamRegistry) {
		this(agentLoader, defaultAppName, defaultSystemCode, "", "prod", executionEventStreamRegistry, null, null, null, null, null, null);
	}

	public ChatController(AgentLoader agentLoader,
			@Value("${assistant.chat.default-app-name:grayscale_agent}") String defaultAppName,
			@Value("${assistant.chat.default-system-code:}")
			String defaultSystemCode,
			@Value("${assistant.chat.default-space-code:}") String defaultSpaceCode,
			@Value("${assistant.chat.default-space-environment:prod}") String defaultSpaceEnvironment,
			@Nullable ExecutionEventStreamRegistry executionEventStreamRegistry,
			@Nullable V3ProtocolAdapter protocolAdapter,
			@Nullable ChatTranscriptPersistenceService transcriptPersistenceService,
			@Nullable ChatThreadStateService chatThreadStateService,
			@Nullable ChatFrontendEventPublisher chatFrontendEventPublisher,
			@Nullable RuntimeSpaceResolver runtimeSpaceResolver) {
		this(agentLoader,
				defaultAppName,
				defaultSystemCode,
				defaultSpaceCode,
				defaultSpaceEnvironment,
				executionEventStreamRegistry,
				protocolAdapter,
				transcriptPersistenceService,
				chatThreadStateService,
				chatFrontendEventPublisher,
				runtimeSpaceResolver,
				null);
	}

	@Autowired
	public ChatController(AgentLoader agentLoader,
			@Value("${assistant.chat.default-app-name:grayscale_agent}") String defaultAppName,
			@Value("${assistant.chat.default-system-code:}")
			String defaultSystemCode,
			@Value("${assistant.chat.default-space-code:}") String defaultSpaceCode,
			@Value("${assistant.chat.default-space-environment:prod}") String defaultSpaceEnvironment,
			@Nullable ExecutionEventStreamRegistry executionEventStreamRegistry,
			@Nullable V3ProtocolAdapter protocolAdapter,
			@Nullable ChatTranscriptPersistenceService transcriptPersistenceService,
			@Nullable ChatThreadStateService chatThreadStateService,
			@Nullable ChatFrontendEventPublisher chatFrontendEventPublisher,
			@Nullable RuntimeSpaceResolver runtimeSpaceResolver,
			@Nullable ScenarioRouter scenarioRouter) {
		this.agentLoader = agentLoader;
		this.defaultAppName = defaultAppName;
		this.defaultSystemCode = defaultSystemCode;
		this.defaultSpaceCode = defaultSpaceCode;
		this.defaultSpaceEnvironment = defaultSpaceEnvironment;
		this.executionEventStreamRegistry = executionEventStreamRegistry;
		this.protocolAdapter = protocolAdapter != null ? protocolAdapter : new V3ProtocolAdapter(new ObjectMapper());
		this.transcriptPersistenceService = transcriptPersistenceService;
		this.chatThreadStateService = chatThreadStateService;
		this.chatFrontendEventPublisher = chatFrontendEventPublisher;
		this.runtimeSpaceResolver = runtimeSpaceResolver;
		this.scenarioRouter = scenarioRouter;
	}

	/**
	 * 发起一轮新的聊天执行。
	 *
	 * <p>前端每发送一条用户消息，都会通过该接口进入运行时主链，
	 * 返回值是结构化的 SSE 事件流。
	 */
	@PostMapping(path = "/run_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> runSse(
			@RequestBody AgentRunRequest request,
			@RequestParam(value = "appName", required = false) String appName,
			@RequestParam(value = "assistantUid", required = false) String assistantUid,
			@RequestParam(value = "systemCode", required = false) String systemCode) {
		AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser();
		normalizeRunRequest(request, appName, authenticatedUser);
		return doRunSse(
				request.appName,
				request.userId,
				request.threadId,
				request.newMessage,
				request.stateDelta);
	}

	/**
	 * 恢复被中断或等待外部反馈的聊天线程。
	 *
	 * <p>典型场景包括页面刷新后恢复待确认表单、审批回调后继续推进流程等。
	 */
	@PostMapping(path = "/resume_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> resumeSse(
			@RequestBody AgentResumeRequest request,
			@RequestParam(value = "appName", required = false) String appName,
			@RequestParam(value = "assistantUid", required = false) String assistantUid,
			@RequestParam(value = "systemCode", required = false) String systemCode) {
		AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser();
		normalizeResumeRequest(request, appName, authenticatedUser);
		return doResumeSse(request);
	}

	// ── 核心执行链 ──────────────────────────────────────────────────

	private Flux<ServerSentEvent<String>> doRunSse(
			String appName,
			String userId,
			String threadId,
			UserMessageDTO newMessage,
			Map<String, Object> stateDelta) {
		if (!StringUtils.hasText(appName)) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty"));
		}
		if (!StringUtils.hasText(threadId)) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty"));
		}
		if (newMessage == null) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "newMessage cannot be null"));
		}
		ChatThreadStateData threadState = loadRunThreadState(threadId, userId);
		List<FrontendEvent> replayEvents = resolveRunReplayEvents(threadState, threadId, newMessage, stateDelta);
		if (!replayEvents.isEmpty()) {
			return Flux.fromIterable(replayEvents).map(this::toSse);
		}
		try {
			// 快速定位：对话主链从这里开始，先装载 Agent，再把用户输入和状态一起送进图运行时。
			Agent agent = agentLoader.loadAgent(appName);
			UserMessage userMessage = newMessage.toUserMessage();
			Map<String, Object> effectiveStateDelta = resolveRoleBindingState(
					mergeFrontendThreadState(stateDelta, threadState),
					userMessage.getText());
			Map<String, Object> agentInput = buildAgentInput(userMessage, effectiveStateDelta);
			String turnId = UUID.randomUUID().toString();
			RunnableConfig.Builder configBuilder = RunnableConfig.builder()
					.threadId(threadId)
					.addMetadata("user_id", userId);
			String effectiveSystemCode = resolveTranscriptSystemCode(effectiveStateDelta);
			if (transcriptPersistenceService != null) {
				transcriptPersistenceService.recordUserMessage(
						threadId,
						userId,
						appName,
						effectiveSystemCode,
						turnId,
						userMessage.getText(),
						effectiveStateDelta);
			}
			return executeAgent(
					agentInput,
					"run_sse",
					agent,
					configBuilder.build(),
					threadId,
					"UNDERSTANDING",
					userId,
					appName,
					effectiveSystemCode,
					turnId,
					effectiveStateDelta);
		}
		catch (Exception e) {
			logger.error("ChatController#doRunSse - reason=agent执行失败, threadId={}", threadId, e);
			return Flux.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent run failed", e));
		}
	}

	private Flux<ServerSentEvent<String>> doResumeSse(AgentResumeRequest request) {
		if (!StringUtils.hasText(request.appName)) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty"));
		}
		if (!StringUtils.hasText(request.threadId)) {
			return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty"));
		}
		try {
			List<FrontendEvent> replayEvents = resolveResumeReplayEvents(
					request.threadId,
					request.userId,
					request.toolFeedbacks != null && !request.toolFeedbacks.isEmpty());
			if (!replayEvents.isEmpty()) {
				return Flux.fromIterable(replayEvents).map(this::toSse);
			}

			Agent agent = agentLoader.loadAgent(request.appName);
			String turnId = UUID.randomUUID().toString();

			InterruptionMetadata.Builder metadataBuilder = InterruptionMetadata.builder();
			if (request.toolFeedbacks != null && !request.toolFeedbacks.isEmpty()) {
				for (ToolRequestConfirmMessageDTO.ToolFeedback toolFeedback : request.toolFeedbacks) {
					InterruptionMetadata.ToolFeedback.FeedbackResult result =
							toolFeedback.getResult() != null
									? InterruptionMetadata.ToolFeedback.FeedbackResult.valueOf(
											toolFeedback.getResult().name())
									: InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED;
					metadataBuilder.addToolFeedback(new InterruptionMetadata.ToolFeedback(
							toolFeedback.getId(),
							toolFeedback.getName(),
							toolFeedback.getArguments(),
							result,
							toolFeedback.getDescription()));
				}
			}

			Map<String, Object> effectiveStateDelta = resolveRoleBindingState(request.stateDelta, null);
			Map<String, Object> agentInput = buildAgentInput(null, effectiveStateDelta);
			RunnableConfig.Builder configBuilder = RunnableConfig.builder()
					.threadId(request.threadId)
					.addMetadata("user_id", request.userId)
					.addHumanFeedback(metadataBuilder.build());
			String effectiveSystemCode = resolveTranscriptSystemCode(effectiveStateDelta);
			if (transcriptPersistenceService != null) {
				transcriptPersistenceService.recordResumeAction(
						request.threadId,
						request.userId,
						request.appName,
						effectiveSystemCode,
						turnId,
						resolveResumeActionText(request),
						effectiveStateDelta);
			}
			return executeAgent(
					agentInput,
					"resume_sse",
					agent,
					configBuilder.build(),
					request.threadId,
					resolveResumeInitialStage(request.threadId, request.userId),
					request.userId,
					request.appName,
					effectiveSystemCode,
					turnId,
					effectiveStateDelta);
		}
		catch (Exception e) {
			logger.error("ChatController#doResumeSse - reason=agent恢复失败, threadId={}", request.threadId, e);
			return Flux.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent resume failed", e));
		}
	}

	/**
	 * 以 SSE 形式持续输出 Agent 执行事件。
	 *
	 * <p>这里会把内部运行事件统一转换为前端协议，并规避上游流式模型收尾阶段的重复输出问题，
	 * 防止同一段文本在前端出现两次。
	 */
	private Flux<ServerSentEvent<String>> executeAgent(
			Map<String, Object> agentInput,
			String executionSource,
			Agent agent,
			RunnableConfig runnableConfig,
			String threadId,
			String initialStage,
			String assistantUid,
			String appName,
			String systemCode,
			String turnId,
			@Nullable Map<String, Object> roleBindingState)
			throws GraphRunnerException {
		// 快速定位：这里把 Agent 图输出、内部执行事件流、聊天持久化三条支路汇总成前端最终看到的 SSE。
		Flux<FrontendEvent> eventFlux = executeAgentEvents(
				agentInput,
				executionSource,
				agent,
				runnableConfig,
				threadId,
				initialStage);
		if (chatFrontendEventPublisher != null
				&& StringUtils.hasText(threadId)
				&& StringUtils.hasText(assistantUid)) {
			eventFlux = eventFlux
					.doOnNext(event -> chatFrontendEventPublisher.publish(
							threadId,
							assistantUid,
							appName,
							systemCode,
							turnId,
							event,
							roleBindingState))
					.doFinally(signalType -> chatFrontendEventPublisher.finishTurn(
							threadId,
							assistantUid,
							appName,
							systemCode,
							turnId));
		}
		else if (transcriptPersistenceService != null
				&& StringUtils.hasText(threadId)
				&& StringUtils.hasText(assistantUid)) {
			eventFlux = eventFlux
					.doOnNext(event -> transcriptPersistenceService.recordFrontendEvent(
							threadId,
							assistantUid,
							appName,
							systemCode,
							turnId,
							event,
							roleBindingState))
					.doFinally(signalType -> transcriptPersistenceService.finishTurn(
							threadId,
							assistantUid,
							appName,
							systemCode,
							turnId));
		}
		return eventFlux.map(this::toSse);
	}

	private Flux<FrontendEvent> executeAgentEvents(
			Map<String, Object> agentInput,
			String executionSource,
			Agent agent,
			RunnableConfig runnableConfig,
			String threadId,
			String initialStage)
			throws GraphRunnerException {
		Flux<NodeOutput> agentStream = agent.stream(agentInput != null ? agentInput : Map.of(), runnableConfig);
		ChunkDeduplicator deduplicator = new ChunkDeduplicator();
		AssistantTextBuffer assistantTextBuffer = new AssistantTextBuffer();
		StageTracker stageTracker = new StageTracker();
		ExecutionEventDeduplicator executionEventDeduplicator = new ExecutionEventDeduplicator();
		FrontendEvent initialEvent = stageTracker.emitInitial(
				initialStage,
				executionSource);

		Flux<FrontendEvent> initialFlux = initialEvent != null
				? Flux.just(initialEvent)
				: Flux.empty();

		Flux<FrontendEvent> liveExecutionFlux = liveExecutionProgressFlux(threadId, executionEventDeduplicator);

		Flux<FrontendEvent> streamFlux = agentStream.concatMap(nodeOutput ->
				Flux.fromIterable(buildSsePayloads(
						nodeOutput,
						threadId,
						deduplicator,
						assistantTextBuffer,
						stageTracker,
						executionEventDeduplicator)));

		Flux<FrontendEvent> finalizedAssistantTextFlux = Flux.defer(() ->
				Flux.fromIterable(assistantTextBuffer.flush(threadId, protocolAdapter, stageTracker)));

		Flux<FrontendEvent> mergedFlux = Flux.merge(liveExecutionFlux, streamFlux.concatWith(finalizedAssistantTextFlux))
				.takeUntil(this::isConversationBoundaryEvent);

		return initialFlux.concatWith(mergedFlux)
				.onErrorResume(error -> {
					logger.error("ChatController#executeAgent - reason=agent流执行出错", error);
					Map<String, Object> errorPayload = new LinkedHashMap<>();
					errorPayload.put("errorType", error.getClass().getSimpleName());
					errorPayload.put("errorMessage", error.getMessage() != null
							? error.getMessage() : "Unknown error occurred");
					return Flux.just(protocolAdapter.errorEvent(threadId, "agent_stream_error", errorPayload));
				});
	}

	private List<FrontendEvent> buildSsePayloads(
			NodeOutput nodeOutput,
			String threadId,
			ChunkDeduplicator deduplicator,
			AssistantTextBuffer assistantTextBuffer,
			StageTracker stageTracker,
			ExecutionEventDeduplicator executionEventDeduplicator) {
		List<FrontendEvent> payloads = new ArrayList<>();
		String node = nodeOutput.node();
		String agentName = nodeOutput.agent();
		Usage tokenUsage = nodeOutput.tokenUsage();

		MessageDTO messageDto = null;
		String chunk = "";
		if (nodeOutput instanceof StreamingOutput<?> streamingOutput) {
			Message message = streamingOutput.message();
			if (message == null) {
				return payloads;
			}
			if (message instanceof AssistantMessage assistantMessage) {
				if (assistantMessage.hasToolCalls()) {
					messageDto = new com.alibaba.cloud.ai.agent.studio.dto.messages.AgentRunResponse(
							node,
							agentName,
							assistantMessage,
							tokenUsage,
							"").getMessage();
				}
				else {
					chunk = deduplicator.nextChunk(
							streamingOutput.getOutputType(), assistantMessage.getText());
					messageDto = new com.alibaba.cloud.ai.agent.studio.dto.messages.AgentRunResponse(
							node,
							agentName,
							assistantMessage,
							tokenUsage,
							chunk).getMessage();
				}
			}
			else if (!(message instanceof UserMessage)) {
				messageDto = new com.alibaba.cloud.ai.agent.studio.dto.messages.AgentRunResponse(
						node,
						agentName,
						message,
						tokenUsage,
						"").getMessage();
			}
		}
		else if (nodeOutput instanceof InterruptionMetadata interruptionMetadata) {
			messageDto = MessageDTO.MessageDTOFactory.fromInterruptionMetadata(interruptionMetadata);
		}

		if (messageDto != null) {
			if (isBufferedAssistantTextMessage(messageDto)) {
				assistantTextBuffer.capture(resolveAssistantAccumulatedText(nodeOutput, messageDto, chunk));
				return payloads;
			}
			if (messageDto instanceof ToolRequestMessageDTO
					|| messageDto instanceof ToolResponseMessageDTO
					|| messageDto instanceof ToolRequestConfirmMessageDTO) {
				assistantTextBuffer.markToolInteraction();
			}
			else {
				assistantTextBuffer.discard();
			}
			boolean suppressInternalNarration = shouldSuppressAssistantNarration(messageDto, chunk)
					|| (isFreeTextMessage(messageDto) && stageTracker.isToolStageActive());
			FrontendEvent stageEvent = suppressInternalNarration
					? null
					: stageTracker.emitForMessage(messageDto, node, threadId);
			if (stageEvent != null) {
				payloads.add(stageEvent);
			}
			if (messageDto instanceof ToolResponseMessageDTO toolResponseMessage) {
				payloads.addAll(adaptToolResponseEvents(
						threadId,
						toolResponseMessage));
				payloads.addAll(extractArtifactExecutionEvents(
						threadId,
						toolResponseMessage,
						executionEventDeduplicator));
			}
			else if (!suppressInternalNarration && shouldEmitMessage(messageDto, chunk)) {
				payloads.add(protocolAdapter.messageEvent(threadId, resolveVisibleMessageText(messageDto, chunk)));
			}
		}

		return payloads;
	}
	List<FrontendEvent> adaptToolResponseEvents(String threadId, ToolResponseMessageDTO message) {
		if (message == null || message.getResponses() == null || message.getResponses().isEmpty()) {
			return List.of();
		}
		List<FrontendEvent> events = new ArrayList<>();
		for (ToolResponseMessageDTO.ToolResponseDTO response : message.getResponses()) {
			if (response == null || !StringUtils.hasText(response.getResponseData())) {
				continue;
			}
			for (FrontendEvent event : protocolAdapter.adapt(threadId, response.getName(), response.getResponseData(), null)) {
				if (shouldExposeFrontendEvent(event)) {
					events.add(event);
				}
			}
		}
		return events;
	}

	private boolean shouldExposeFrontendEvent(FrontendEvent event) {
		if (event == null || event.eventType() == null) {
			return false;
		}
		if (event.eventType() != FrontendEventType.MESSAGE) {
			return true;
		}
		Map<String, Object> payload = event.payload();
		return FrontendMessageVisibilitySupport.isVisibleAssistantText(asText(payload != null ? payload.get("text") : null));
	}
	List<FrontendEvent> extractArtifactExecutionEvents(String threadId, ToolResponseMessageDTO message) {
		return extractArtifactExecutionEvents(threadId, message, null);
	}

	List<FrontendEvent> extractArtifactExecutionEvents(
			String threadId,
			ToolResponseMessageDTO message,
			@Nullable ExecutionEventDeduplicator executionEventDeduplicator) {
		if (message == null || message.getResponses() == null || message.getResponses().isEmpty()) {
			return List.of();
		}
		List<FrontendEvent> events = new ArrayList<>();
		for (ToolResponseMessageDTO.ToolResponseDTO response : message.getResponses()) {
			if (response == null || !StringUtils.hasText(response.getResponseData())) {
				continue;
			}
			try {
				Map<String, Object> responsePayload = mapper.readValue(
						response.getResponseData(), new TypeReference<Map<String, Object>>() {
						});
				Object rawExecutionEvents = responsePayload.get("executionEvents");
				if (!(rawExecutionEvents instanceof List<?> rawEvents) || rawEvents.isEmpty()) {
					continue;
				}
				for (Object rawEvent : rawEvents) {
					Map<String, Object> eventPayload = mapper.convertValue(
							rawEvent, new TypeReference<LinkedHashMap<String, Object>>() {
							});
					if (!StringUtils.hasText(asText(eventPayload.get("toolName")))
							&& StringUtils.hasText(response.getName())) {
						eventPayload.put("toolName", response.getName());
					}
					copyExecutionContractMeta(eventPayload, asMap(eventPayload.get("payload")));
					if (isInternalExecutionPayload(eventPayload)) {
						continue;
					}
					if (executionEventDeduplicator == null || executionEventDeduplicator.shouldEmit(eventPayload)) {
						events.add(protocolAdapter.executionProgressEvent(threadId, eventPayload));
						Map<String, Object> taskPayload = protocolAdapter.executionTaskPayload(eventPayload);
						if (!taskPayload.isEmpty()) {
							events.add(protocolAdapter.taskStateEvent(threadId, taskPayload));
						}
					}
				}
			}
			catch (Exception e) {
				logger.debug("ChatController#extractArtifactExecutionEvents - skip invalid tool response payload", e);
			}
		}
		return events;
	}

	Flux<FrontendEvent> liveExecutionProgressFlux(
			String threadId,
			ExecutionEventDeduplicator executionEventDeduplicator) {
		if (executionEventStreamRegistry == null || !StringUtils.hasText(threadId)) {
			return Flux.empty();
		}
		ExecutionEventStreamRegistry.ExecutionEventSubscription subscription = executionEventStreamRegistry.open(threadId);
		return subscription.flux()
				.filter(event -> !isInternalExecutionEvent(event))
				.filter(executionEventDeduplicator::shouldEmit)
				.takeUntil(this::isTerminalExecutionEvent)
				.flatMapIterable(event -> toExecutionFrontendEvents(threadId, event))
				.doFinally(signalType -> subscription.close());
	}

	private boolean isTerminalExecutionEvent(ExecutionEvent event) {
		if (event == null || isInternalExecutionEvent(event)) {
			return false;
		}
		if (event.lifecycleStatus() == ExecutionLifecycleStatus.WAITING_APPROVAL) {
			return true;
		}
		return event.eventType() == ExecutionEventType.RUN_COMPLETED
				|| event.eventType() == ExecutionEventType.RUN_FAILED;
	}

	List<FrontendEvent> toExecutionFrontendEvents(String threadId, ExecutionEvent event) {
		if (event == null || isInternalExecutionEvent(event)) {
			return List.of();
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("runId", event.runId());
		payload.put("artifactCode", event.artifactCode());
		payload.put("artifactType", event.artifactType());
		payload.put("stepId", event.stepId());
		payload.put("sequence", event.sequence());
		payload.put("eventType", event.eventType() != null ? event.eventType().name() : null);
		payload.put("lifecycleStatus", event.lifecycleStatus() != null ? event.lifecycleStatus().name() : null);
		payload.put("occurredAt", event.occurredAt() != null ? event.occurredAt().toString() : null);
		copyExecutionContractMeta(payload, event.payload());
		payload.put("payload", event.payload());
		List<FrontendEvent> events = new ArrayList<>();
		events.add(protocolAdapter.executionProgressEvent(threadId, payload));
		Map<String, Object> taskPayload = protocolAdapter.executionTaskPayload(payload);
		if (!taskPayload.isEmpty()) {
			events.add(protocolAdapter.taskStateEvent(threadId, taskPayload));
		}
		return events;
	}

	FrontendEvent toExecutionProgressEvent(String threadId, ExecutionEvent event) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("runId", event.runId());
		payload.put("artifactCode", event.artifactCode());
		payload.put("artifactType", event.artifactType());
		payload.put("stepId", event.stepId());
		payload.put("sequence", event.sequence());
		payload.put("eventType", event.eventType() != null ? event.eventType().name() : null);
		payload.put("lifecycleStatus", event.lifecycleStatus() != null ? event.lifecycleStatus().name() : null);
		payload.put("occurredAt", event.occurredAt() != null ? event.occurredAt().toString() : null);
		copyExecutionContractMeta(payload, event.payload());
		payload.put("payload", event.payload());
		return protocolAdapter.executionProgressEvent(threadId, payload);
	}

	boolean isConversationBoundaryEvent(FrontendEvent event) {
		if (event == null || event.eventType() == null) {
			return false;
		}
		return switch (event.eventType()) {
			case FORM_STATE, RESULT, ERROR -> true;
			case TASK_STATE -> isTaskBoundaryEvent(event.payload());
			default -> false;
		};
	}

	private boolean isTaskBoundaryEvent(Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return false;
		}
		String status = asText(payload.get("status"));
		if ("WAITING_APPROVAL".equalsIgnoreCase(status)) {
			return true;
		}
		if (Boolean.TRUE.equals(payload.get("background"))) {
			return true;
		}
		return Boolean.TRUE.equals(payload.get("detached"));
	}

	private boolean isInternalExecutionEvent(ExecutionEvent event) {
		return event != null && isInternalExecutionPayload(event.payload());
	}

	private boolean isInternalExecutionPayload(Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return false;
		}
		if (Boolean.TRUE.equals(payload.get("internal"))) {
			return true;
		}
		Map<String, Object> nestedPayload = asMap(payload.get("payload"));
		return Boolean.TRUE.equals(nestedPayload.get("internal"));
	}

	private void copyExecutionContractMeta(Map<String, Object> target, Map<String, Object> sourcePayload) {
		if (target == null || sourcePayload == null || sourcePayload.isEmpty()) {
			return;
		}
		if (sourcePayload.containsKey("internal")) {
			target.put("internal", Boolean.TRUE.equals(sourcePayload.get("internal")));
		}
		putIfHasText(target, "toolType", asText(sourcePayload.get("toolType")));
		putIfHasText(target, "visibility", asText(sourcePayload.get("visibility")));
		putIfHasText(target, "invocationPolicy", asText(sourcePayload.get("invocationPolicy")));
	}

	private void putIfHasText(Map<String, Object> target, String key, String value) {
		if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
			target.put(key, value);
		}
	}

	private Map<String, Object> asMap(Object value) {
		if (value instanceof Map<?, ?> mapValue) {
			return mapper.convertValue(mapValue, new TypeReference<LinkedHashMap<String, Object>>() {
			});
		}
		return Map.of();
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private ServerSentEvent<String> toSse(Object payload) {
		try {
			return ServerSentEvent.<String>builder()
					.data(mapper.writeValueAsString(payload))
					.build();
		}
		catch (Exception e) {
			logger.error("ChatController#toSse - reason=JSON序列化失败", e);
			return errorSse("Failed to serialize response");
		}
	}

	private boolean shouldEmitMessage(MessageDTO message, String chunk) {
		if (message == null) {
			return false;
		}
		if (message instanceof ToolRequestMessageDTO
				|| message instanceof ToolResponseMessageDTO
				|| message instanceof ToolRequestConfirmMessageDTO) {
			return false;
		}
		String messageType = message.getMessageType();
		if ("user".equalsIgnoreCase(messageType) || "tool".equalsIgnoreCase(messageType)) {
			return false;
		}
		if ("assistant".equalsIgnoreCase(messageType)) {
			return FrontendMessageVisibilitySupport.isVisibleAssistantText(chunk);
		}
		return FrontendMessageVisibilitySupport.isVisibleAssistantText(message.getContent());
	}

	private String resolveVisibleMessageText(MessageDTO message, String chunk) {
		if (message == null) {
			return "";
		}
		if ("assistant".equalsIgnoreCase(message.getMessageType()) && StringUtils.hasText(chunk)) {
			return chunk;
		}
		return message.getContent() != null ? message.getContent() : "";
	}

	private boolean shouldSuppressAssistantNarration(MessageDTO message, String chunk) {
		if (message == null || !"assistant".equalsIgnoreCase(message.getMessageType())) {
			return false;
		}
		// 内部规划说明不应进入前端 SSE，也不应驱动 DONE 阶段闪烁。
		return FrontendMessageVisibilitySupport.isInternalPlanningNarration(resolveVisibleMessageText(message, chunk));
	}

	private boolean isBufferedAssistantTextMessage(MessageDTO message) {
		return message != null
				&& "assistant".equalsIgnoreCase(message.getMessageType())
				&& !(message instanceof ToolRequestMessageDTO)
				&& !(message instanceof ToolResponseMessageDTO)
				&& !(message instanceof ToolRequestConfirmMessageDTO);
	}

	private boolean isFreeTextMessage(MessageDTO message) {
		return message != null
				&& !(message instanceof ToolRequestMessageDTO)
				&& !(message instanceof ToolResponseMessageDTO)
				&& !(message instanceof ToolRequestConfirmMessageDTO);
	}

	private String resolveAssistantAccumulatedText(NodeOutput nodeOutput, MessageDTO message, String chunk) {
		if (nodeOutput instanceof StreamingOutput<?> streamingOutput
				&& streamingOutput.message() instanceof AssistantMessage assistantMessage
				&& StringUtils.hasText(assistantMessage.getText())) {
			return assistantMessage.getText();
		}
		return resolveVisibleMessageText(message, chunk);
	}

	static final class StageTracker {

		private final AtomicReference<String> lastStage = new AtomicReference<>("");

		FrontendEvent emitInitial(String stage, String source) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("source", source);
			return emit(stage, payload, null);
		}

		FrontendEvent emitForMessage(MessageDTO message, String node, String threadId) {
			if (message == null) {
				return null;
			}
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("messageType", message.getMessageType());
			String toolName = resolveToolName(message);
			if (StringUtils.hasText(toolName)) {
				payload.put("toolName", toolName);
			}
			String stage = resolveStage(message);
			if (StringUtils.hasText(stage)) {
				return emit(stage, payload, threadId);
			}
			if (isTerminalAssistantReply(message)) {
				return emit("DONE", payload, threadId);
			}
			return null;
		}

		private FrontendEvent emit(String stage, Map<String, Object> payload, String threadId) {
			if (!StringUtils.hasText(stage)) {
				return null;
			}
			String previous = lastStage.get();
			if (stage.equals(previous)) {
				return null;
			}
			lastStage.set(stage);
			return new FrontendEvent(
					V3ProtocolAdapter.PROTOCOL_VERSION,
					UUID.randomUUID().toString(),
					threadId,
					Instant.now().toString(),
					FrontendEventType.STAGE,
					toFrontendStage(stage),
					payload != null ? payload : Map.of());
		}

		private FrontendStage toFrontendStage(String stage) {
			if (!StringUtils.hasText(stage)) {
				return FrontendStage.UNDERSTANDING;
			}
			return switch (stage.trim().toUpperCase()) {
				case "COLLECTING" -> FrontendStage.COLLECTING;
				case "CONFIRMING", "READY_TO_CONFIRM" -> FrontendStage.CONFIRMING;
				case "EXECUTING" -> FrontendStage.EXECUTING;
				case "WAITING_APPROVAL" -> FrontendStage.WAITING_APPROVAL;
				case "DONE" -> FrontendStage.DONE;
				case "ERROR" -> FrontendStage.ERROR;
				default -> FrontendStage.UNDERSTANDING;
			};
		}

		boolean isToolStageActive() {
			String current = lastStage.get();
			if (!StringUtils.hasText(current)) {
				return false;
			}
			return switch (current.trim().toUpperCase()) {
				case "COLLECTING", "CONFIRMING", "READY_TO_CONFIRM", "EXECUTING", "WAITING_APPROVAL" -> true;
				default -> false;
			};
		}

		private String resolveStage(MessageDTO message) {
			if (message instanceof ToolRequestConfirmMessageDTO) {
				return "CONFIRMING";
			}
			if (message instanceof ToolRequestMessageDTO toolRequestMessage) {
				return resolveToolStage(firstToolCallName(toolRequestMessage));
			}
			if (message instanceof ToolResponseMessageDTO toolResponseMessage) {
				return resolveToolStage(firstToolResponseName(toolResponseMessage));
			}
			return null;
		}

		private String resolveToolName(MessageDTO message) {
			if (message instanceof ToolRequestConfirmMessageDTO) {
				return "slot_confirm";
			}
			if (message instanceof ToolRequestMessageDTO toolRequestMessage) {
				return firstToolCallName(toolRequestMessage);
			}
			if (message instanceof ToolResponseMessageDTO toolResponseMessage) {
				return firstToolResponseName(toolResponseMessage);
			}
			return null;
		}

		private boolean isTerminalAssistantReply(MessageDTO message) {
			if (!"assistant".equals(message.getMessageType()) || !StringUtils.hasText(message.getContent())) {
				return false;
			}
			String previous = lastStage.get();
			return "UNDERSTANDING".equals(previous) || "EXECUTING".equals(previous);
		}

		private String resolveToolStage(String toolName) {
			if (!StringUtils.hasText(toolName)) {
				return null;
			}
			return switch (toolName) {
				case "slot_collect" -> "COLLECTING";
				case "slot_confirm" -> "CONFIRMING";
				default -> "EXECUTING";
			};
		}

		private String firstToolCallName(ToolRequestMessageDTO message) {
			if (message.getToolCalls() == null || message.getToolCalls().isEmpty()) {
				return null;
			}
			return message.getToolCalls().get(0).getName();
		}

		private String firstToolResponseName(ToolResponseMessageDTO message) {
			if (message.getResponses() == null || message.getResponses().isEmpty()) {
				return null;
			}
			return message.getResponses().get(0).getName();
		}
	}

	static class ChunkDeduplicator {

		private final AtomicReference<String> lastAccumulatedText = new AtomicReference<>("");

		String nextChunk(OutputType outputType, String currentText) {
			String safeCurrent = currentText != null ? currentText : "";
			if (safeCurrent.isEmpty()) {
				return "";
			}
			String last = lastAccumulatedText.get();
			if (safeCurrent.equals(last)) {
				return "";
			}
			if (safeCurrent.startsWith(last)) {
				lastAccumulatedText.set(safeCurrent);
				return safeCurrent.substring(last.length());
			}
			if (last.startsWith(safeCurrent)) {
				lastAccumulatedText.set(safeCurrent);
				return "";
			}
			if (outputType == OutputType.AGENT_MODEL_FINISHED) {
				lastAccumulatedText.set(safeCurrent);
				return "";
			}
			lastAccumulatedText.set(safeCurrent);
			return safeCurrent;
		}
	}

	static final class AssistantTextBuffer {

		private String accumulatedText;

		private boolean toolInteractionObserved;

		void capture(String text) {
			if (toolInteractionObserved || !StringUtils.hasText(text)) {
				return;
			}
			accumulatedText = text;
		}

		void discard() {
			accumulatedText = null;
		}

		void markToolInteraction() {
			toolInteractionObserved = true;
			accumulatedText = null;
		}

		List<FrontendEvent> flush(String threadId, V3ProtocolAdapter protocolAdapter, StageTracker stageTracker) {
			if (toolInteractionObserved) {
				toolInteractionObserved = false;
				accumulatedText = null;
				return List.of();
			}
			if (!StringUtils.hasText(accumulatedText)) {
				return List.of();
			}
			String finalText = accumulatedText.trim();
			accumulatedText = null;
			if (!FrontendMessageVisibilitySupport.isVisibleAssistantText(finalText)) {
				return List.of();
			}
			List<FrontendEvent> events = new ArrayList<>();
			FrontendEvent stageEvent = stageTracker.emit("DONE", Map.of("messageType", "assistant"), threadId);
			if (stageEvent != null) {
				events.add(stageEvent);
			}
			events.add(protocolAdapter.messageEvent(threadId, finalText));
			return events;
		}
	}

	static final class ExecutionEventDeduplicator {

		private final Set<String> seenEventKeys = ConcurrentHashMap.newKeySet();

		boolean shouldEmit(ExecutionEvent event) {
			if (event == null) {
				return false;
			}
			return seenEventKeys.add(buildKey(
					event.runId(),
					event.sequence(),
					event.eventType() != null ? event.eventType().name() : null,
					event.stepId()));
		}

		boolean shouldEmit(Map<String, Object> eventPayload) {
			if (eventPayload == null || eventPayload.isEmpty()) {
				return false;
			}
			return seenEventKeys.add(buildKey(
					asText(eventPayload.get("runId")),
					eventPayload.get("sequence"),
					asText(eventPayload.get("eventType")),
					asText(eventPayload.get("stepId"))));
		}

		private String buildKey(String runId, Object sequence, String eventType, String stepId) {
			return String.join(":",
					normalize(runId),
					normalize(sequence != null ? String.valueOf(sequence) : null),
					normalize(eventType),
					normalize(stepId));
		}

		private String normalize(String value) {
			return StringUtils.hasText(value) ? value.trim() : "_";
		}

		private String asText(Object value) {
			if (value == null) {
				return null;
			}
			String text = String.valueOf(value).trim();
			return StringUtils.hasText(text) ? text : null;
		}
	}
	// ── Request normalisation helpers ───────────────────────────────────

	private void normalizeRunRequest(
			AgentRunRequest request, String appName, AuthenticatedUserContext authenticatedUser) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body cannot be null");
		}
		request.appName = resolveAppName(request.appName, appName);
		if (!StringUtils.hasText(request.threadId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty");
		}
		if (request.newMessage == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newMessage cannot be null");
		}
		String userId = requireAuthenticatedUserId(authenticatedUser);
		request.userId = userId;
		request.streaming = true;
		request.stateDelta = mergeStateDelta(
				request.stateDelta,
				request.threadId,
				userId,
				resolveSystemCode(authenticatedUser),
				request.appName,
				request.newMessage.toUserMessage().getText());
	}

	List<FrontendEvent> resolveResumeReplayEvents(String threadId, String assistantUid, boolean hasToolFeedback) {
		if (hasToolFeedback || chatThreadStateService == null
				|| !StringUtils.hasText(threadId) || !StringUtils.hasText(assistantUid)) {
			return List.of();
		}
		try {
			return buildReplayEvents(chatThreadStateService.getThreadState(threadId, assistantUid), "resume_sse");
		}
		catch (Exception ex) {
			logger.debug("ChatController#resolveResumeReplayEvents - reason=thread快照不可用, threadId={}", threadId, ex);
			return List.of();
		}
	}

	List<FrontendEvent> resolveRunReplayEvents(
			String threadId,
			String assistantUid,
			@Nullable UserMessageDTO newMessage,
			@Nullable Map<String, Object> stateDelta) {
		if (chatThreadStateService == null
				|| !StringUtils.hasText(threadId)
				|| !StringUtils.hasText(assistantUid)) {
			return List.of();
		}
		try {
			return resolveRunReplayEvents(
					chatThreadStateService.getThreadState(threadId, assistantUid),
					threadId,
					newMessage,
					stateDelta);
		}
		catch (Exception ex) {
			logger.debug("ChatController#resolveRunReplayEvents - reason=thread快照不可用, threadId={}", threadId, ex);
			return List.of();
		}
	}

	private List<FrontendEvent> resolveRunReplayEvents(
			@Nullable ChatThreadStateData threadState,
			String threadId,
			@Nullable UserMessageDTO newMessage,
			@Nullable Map<String, Object> stateDelta) {
		if (threadState == null || !shouldReplayPendingForm(threadState, newMessage, stateDelta)) {
			return List.of();
		}
		logger.info(
				"ChatController#resolveRunReplayEvents - replay pending form instead of re-entering model, threadId={}",
				threadId);
		return buildReplayEvents(threadState, "run_sse");
	}

	@Nullable
	private ChatThreadStateData loadRunThreadState(String threadId, String assistantUid) {
		if (chatThreadStateService == null
				|| !StringUtils.hasText(threadId)
				|| !StringUtils.hasText(assistantUid)) {
			return null;
		}
		try {
			return chatThreadStateService.getThreadState(threadId, assistantUid);
		}
		catch (Exception ex) {
			logger.debug("ChatController#loadRunThreadState - reason=thread蹇収涓嶅彲鐢? threadId={}", threadId, ex);
			return null;
		}
	}

	private boolean shouldReplayPendingForm(
			@Nullable ChatThreadStateData threadState,
			@Nullable UserMessageDTO newMessage,
			@Nullable Map<String, Object> stateDelta) {
        if (threadState == null
                || !threadState.unfinished()
                || !"FORM_CARD".equalsIgnoreCase(threadState.pendingCardType())
                || threadState.pendingForm() == null
                || threadState.pendingForm().isEmpty()) {
            return false;
        }
        if (Boolean.TRUE.equals(threadState.pendingForm().get("readOnly"))) {
            return false;
        }
        if (hasExplicitRunReplayInput(stateDelta)) {
            return false;
        }
		String incomingText = newMessage != null ? asText(newMessage.getContent()) : null;
		if (!StringUtils.hasText(incomingText)) {
			return true;
		}
		return isPromptEcho(incomingText, asText(threadState.pendingForm().get("message")))
				|| isPromptEcho(incomingText, threadState.lastMessage());
	}

	private Map<String, Object> mergeFrontendThreadState(
			@Nullable Map<String, Object> stateDelta,
			@Nullable ChatThreadStateData threadState) {
		if (threadState == null || !threadState.unfinished()) {
			return stateDelta;
		}
		Map<String, Object> frontendThreadState = buildFrontendThreadStateSnapshot(threadState);
		if (frontendThreadState.isEmpty()) {
			return stateDelta;
		}
		Map<String, Object> merged = stateDelta == null || stateDelta.isEmpty()
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(stateDelta);
		merged.putIfAbsent(AssistantStateKeys.FRONTEND_THREAD_STATE, frontendThreadState);
		return merged;
	}

	private Map<String, Object> buildFrontendThreadStateSnapshot(ChatThreadStateData threadState) {
		if (threadState == null) {
			return Map.of();
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		putIfHasText(snapshot, "status", threadState.status());
		putIfHasText(snapshot, "phase", threadState.phase());
		snapshot.put("unfinished", threadState.unfinished());
		snapshot.put("canResume", threadState.canResume());
		putIfHasText(snapshot, "toolCode", threadState.toolCode());
		putIfHasText(snapshot, "pendingCardType", threadState.pendingCardType());
		putIfHasText(snapshot, "lastMessage", threadState.lastMessage());
		if (threadState.pendingForm() != null && !threadState.pendingForm().isEmpty()) {
			snapshot.put("pendingForm", new LinkedHashMap<>(threadState.pendingForm()));
		}
		if (threadState.lastResult() != null && !threadState.lastResult().isEmpty()) {
			snapshot.put("lastResult", new LinkedHashMap<>(threadState.lastResult()));
		}
		if (threadState.tasks() != null && !threadState.tasks().isEmpty()) {
			snapshot.put("tasks", threadState.tasks());
		}
		if (threadState.notifications() != null && !threadState.notifications().isEmpty()) {
			snapshot.put("notifications", threadState.notifications());
		}
		if (threadState.nextAction() != null && !threadState.nextAction().isEmpty()) {
			snapshot.put("nextAction", new LinkedHashMap<>(threadState.nextAction()));
		}
		return snapshot;
	}

	private boolean hasExplicitRunReplayInput(@Nullable Map<String, Object> stateDelta) {
		if (stateDelta == null || stateDelta.isEmpty()) {
			return false;
		}
		for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
			String key = entry.getKey();
			if (!StringUtils.hasText(key)) {
				continue;
			}
			if (AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS.equals(key)) {
				if (hasExplicitCurrentTurnSlotInputs(entry.getValue())) {
					return true;
				}
				continue;
			}
			if (RUN_REPLAY_STATE_KEYS.contains(key)) {
				continue;
			}
			if (hasMeaningfulValue(entry.getValue())) {
				return true;
			}
		}
		return false;
	}

	private boolean hasExplicitCurrentTurnSlotInputs(@Nullable Object value) {
		if (!(value instanceof Map<?, ?> slotInputs) || slotInputs.isEmpty()) {
			return false;
		}
		for (Map.Entry<?, ?> entry : slotInputs.entrySet()) {
			String key = entry.getKey() != null ? String.valueOf(entry.getKey()) : null;
			if (!StringUtils.hasText(key)
					|| AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS.equals(key)
					|| RUN_REPLAY_STATE_KEYS.contains(key)) {
				continue;
			}
			if (hasMeaningfulValue(entry.getValue())) {
				return true;
			}
		}
		return false;
	}

	private boolean hasMeaningfulValue(@Nullable Object value) {
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

	private boolean isPromptEcho(@Nullable String incomingText, @Nullable String candidate) {
		String normalizedIncoming = normalizeReplayText(incomingText);
		String normalizedCandidate = normalizeReplayText(candidate);
		if (!StringUtils.hasText(normalizedIncoming) || !StringUtils.hasText(normalizedCandidate)) {
			return false;
		}
		if (normalizedIncoming.equals(normalizedCandidate)) {
			return true;
		}
		return Math.min(normalizedIncoming.length(), normalizedCandidate.length()) >= 8
				&& (normalizedIncoming.contains(normalizedCandidate)
				|| normalizedCandidate.contains(normalizedIncoming));
	}

	private String normalizeReplayText(@Nullable String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		return text.replaceAll("[\\p{P}\\p{S}\\s]+", "").trim().toLowerCase();
	}

	private List<FrontendEvent> buildReplayEvents(@Nullable ChatThreadStateData threadState, String source) {
		if (threadState == null || !StringUtils.hasText(threadState.threadId())) {
			return List.of();
		}
		FrontendStage replayStage = resolveResumeReplayStage(threadState.phase(), threadState.status());
		FrontendEvent stageEvent = protocolAdapter.stageEvent(
				threadState.threadId(),
				replayStage,
				Map.of("source", source));
		if ("FORM_CARD".equalsIgnoreCase(threadState.pendingCardType()) && !threadState.pendingForm().isEmpty()) {
			return List.of(stageEvent, protocolAdapter.formStateEvent(threadState.threadId(), threadState.pendingForm()));
		}
		if ("RESULT_CARD".equalsIgnoreCase(threadState.pendingCardType()) && !threadState.lastResult().isEmpty()) {
			return List.of(stageEvent, protocolAdapter.resultEvent(threadState.threadId(), threadState.lastResult()));
		}
		if (!"TASK_CARD".equalsIgnoreCase(threadState.pendingCardType()) || threadState.tasks().isEmpty()) {
			return List.of();
		}
		List<FrontendEvent> replayEvents = new ArrayList<>();
		replayEvents.add(stageEvent);
		for (var task : threadState.tasks()) {
			replayEvents.add(protocolAdapter.taskStateEvent(
					threadState.threadId(),
					new LinkedHashMap<>(mapper.convertValue(task, new TypeReference<Map<String, Object>>() {
					}))));
		}
		return replayEvents;
	}

	// 恢复未完成线程时优先回放持久化卡片，避免重新走模型总结造成抖动和长连接悬挂。
	private FrontendStage resolveResumeReplayStage(@Nullable String phase, @Nullable String status) {
		String stageName = normalizeResumeInitialStage(phase, status);
		try {
			return FrontendStage.valueOf(stageName);
		}
		catch (IllegalArgumentException ex) {
			return FrontendStage.EXECUTING;
		}
	}

	private void normalizeResumeRequest(
			AgentResumeRequest request, String appName, AuthenticatedUserContext authenticatedUser) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body cannot be null");
		}
		request.appName = resolveAppName(request.appName, appName);
		if (!StringUtils.hasText(request.threadId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty");
		}
		request.userId = requireAuthenticatedUserId(authenticatedUser);
		request.streaming = true;
		request.stateDelta = mergeStateDelta(
				request.stateDelta,
				request.threadId,
				request.userId,
				resolveSystemCode(authenticatedUser),
				request.appName,
				null);
	}

	private String resolveAppName(String... candidates) {
		for (String candidate : candidates) {
			if (StringUtils.hasText(candidate)) {
				return candidate;
			}
		}
		if (!StringUtils.hasText(defaultAppName)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty");
		}
		return defaultAppName;
	}

	private Map<String, Object> mergeStateDelta(
			Map<String, Object> baseStateDelta, String threadId, String assistantUid, String systemCode,
			String agentAppCode, @Nullable String currentTurnUserInput) {
		Map<String, Object> merged = new LinkedHashMap<>();
		if (baseStateDelta != null && !baseStateDelta.isEmpty()) {
			merged.putAll(baseStateDelta);
			Map<String, Object> currentTurnSlotInputs = buildCurrentTurnSlotInputs(baseStateDelta);
			if (!currentTurnSlotInputs.isEmpty()) {
				merged.put(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, currentTurnSlotInputs);
			}
		}
		merged.put(AssistantStateKeys.CURRENT_TURN_USER_INPUT,
				StringUtils.hasText(currentTurnUserInput) ? currentTurnUserInput : null);
		if (StringUtils.hasText(threadId)) {
			merged.put(AssistantStateKeys.THREAD_ID, threadId);
		}
		if (StringUtils.hasText(assistantUid)) {
			merged.put(AssistantStateKeys.ASSISTANT_UID, assistantUid);
		}
		if (StringUtils.hasText(systemCode)) {
			merged.put(AssistantStateKeys.SYSTEM_CODE, systemCode);
		}
		if (StringUtils.hasText(agentAppCode)) {
			merged.put(AssistantStateKeys.AGENT_APP_CODE, agentAppCode);
		}
		if (StringUtils.hasText(defaultSpaceCode)
				&& !StringUtils.hasText(asString(merged.get(AssistantStateKeys.SPACE_CODE)))) {
			merged.put(AssistantStateKeys.SPACE_CODE, defaultSpaceCode);
		}
		if (!StringUtils.hasText(asString(merged.get(AssistantStateKeys.SPACE_ENVIRONMENT)))) {
			merged.put(AssistantStateKeys.SPACE_ENVIRONMENT, resolveDefaultSpaceEnvironment());
		}
		if (runtimeSpaceResolver != null) {
			RuntimeSpaceResolver.ResolvedSpace resolvedSpace = runtimeSpaceResolver.resolve(merged);
			if (resolvedSpace.spaceId() != null && merged.get(AssistantStateKeys.SPACE_ID) == null) {
				merged.put(AssistantStateKeys.SPACE_ID, resolvedSpace.spaceId());
			}
			if (StringUtils.hasText(resolvedSpace.spaceCode())
					&& !StringUtils.hasText(asString(merged.get(AssistantStateKeys.SPACE_CODE)))) {
				merged.put(AssistantStateKeys.SPACE_CODE, resolvedSpace.spaceCode());
			}
			if (StringUtils.hasText(resolvedSpace.environment())
					&& !StringUtils.hasText(asString(merged.get(AssistantStateKeys.SPACE_ENVIRONMENT)))) {
				merged.put(AssistantStateKeys.SPACE_ENVIRONMENT, resolvedSpace.environment());
			}
		}
		return merged.isEmpty() ? null : merged;
	}

	// 快速定位：run_sse / resume_sse 最终都在这里把会话输入转换成 Graph 的输入状态。
	// 结构化表单值必须直接进入图输入状态，不能只挂在 RunnableConfig metadata 上。
	private Map<String, Object> buildCurrentTurnSlotInputs(@Nullable Map<String, Object> baseStateDelta) {
		Map<String, Object> currentTurnSlotInputs = new LinkedHashMap<>();
		if (baseStateDelta == null || baseStateDelta.isEmpty()) {
			return currentTurnSlotInputs;
		}
		Object rawNestedInputs = baseStateDelta.get(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS);
		if (rawNestedInputs instanceof Map<?, ?> nestedInputs) {
			nestedInputs.forEach((key, value) -> {
				if (key != null) {
					currentTurnSlotInputs.put(String.valueOf(key), value);
				}
			});
		}
		for (Map.Entry<String, Object> entry : baseStateDelta.entrySet()) {
			String key = entry.getKey();
			if (!StringUtils.hasText(key)
					|| AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS.equals(key)
					|| RUN_REPLAY_STATE_KEYS.contains(key)) {
				continue;
			}
			currentTurnSlotInputs.put(key, entry.getValue());
		}
		return currentTurnSlotInputs;
	}

	private Map<String, Object> buildAgentInput(
			@Nullable UserMessage userMessage,
			@Nullable Map<String, Object> stateDelta) {
		Map<String, Object> agentInput = new LinkedHashMap<>();
		if (stateDelta != null && !stateDelta.isEmpty()) {
			agentInput.putAll(stateDelta);
		}
		if (userMessage != null) {
			agentInput.put("messages", List.of(userMessage));
			if (StringUtils.hasText(userMessage.getText())) {
				agentInput.put("input", userMessage.getText());
			}
		}
		return agentInput;
	}

	private Map<String, Object> resolveRoleBindingState(
			@Nullable Map<String, Object> stateDelta,
			@Nullable String latestInput) {
		if ((stateDelta == null || stateDelta.isEmpty()) && !StringUtils.hasText(latestInput)) {
			return stateDelta;
		}
		Map<String, Object> resolved = stateDelta == null || stateDelta.isEmpty()
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(stateDelta);
		if (scenarioRouter == null
				|| !StringUtils.hasText(latestInput)
				|| StringUtils.hasText(asString(resolved.get(AssistantStateKeys.ROLE_SCENARIO_CODE)))) {
			return resolved;
		}
		// 岗位包模式下，场景自动补全就发生在这里；后续 slot_collect / artifact_execute 都依赖这个状态。
		scenarioRouter.resolveScenario(resolved, latestInput)
				.ifPresent(scenarioCode -> resolved.put(AssistantStateKeys.ROLE_SCENARIO_CODE, scenarioCode));
		return resolved;
	}

	private String resolveDefaultSpaceEnvironment() {
		return StringUtils.hasText(defaultSpaceEnvironment) ? defaultSpaceEnvironment : "prod";
	}

	private String asString(Object value) {
		return value instanceof String text ? text : null;
	}

	private String resolveTranscriptSystemCode(Map<String, Object> stateDelta) {
		String stateSystemCode = stateDelta != null ? asString(stateDelta.get(AssistantStateKeys.SYSTEM_CODE)) : null;
		return StringUtils.hasText(stateSystemCode) ? stateSystemCode : defaultSystemCode;
	}

	// 恢复会话时优先复用已持久化阶段，避免前端先收到错误的 EXECUTING 闪烁。
	String resolveResumeInitialStage(String threadId, String assistantUid) {
		if (chatThreadStateService == null || !StringUtils.hasText(threadId) || !StringUtils.hasText(assistantUid)) {
			return "EXECUTING";
		}
		try {
			var threadState = chatThreadStateService.getThreadState(threadId, assistantUid);
			return normalizeResumeInitialStage(threadState.phase(), threadState.status());
		}
		catch (Exception ex) {
			logger.debug("ChatController#resolveResumeInitialStage - reason=thread阶段不可用, threadId={}", threadId, ex);
			return "EXECUTING";
		}
	}

	private String normalizeResumeInitialStage(@Nullable String phase, @Nullable String status) {
		String normalizedPhase = StringUtils.hasText(phase) ? phase.trim().toUpperCase() : null;
		if (!StringUtils.hasText(normalizedPhase) && StringUtils.hasText(status)) {
			normalizedPhase = switch (status.trim().toUpperCase()) {
				case "WAITING_CONFIRMATION" -> "CONFIRMING";
				case "WAITING_INPUT" -> "COLLECTING";
				case "WAITING_APPROVAL" -> "WAITING_APPROVAL";
				case "COMPLETED" -> "DONE";
				case "FAILED" -> "ERROR";
				default -> status.trim().toUpperCase();
			};
		}
		if (!StringUtils.hasText(normalizedPhase)) {
			return "EXECUTING";
		}
		return switch (normalizedPhase) {
			case "WAITING_CONFIRMATION", "CONFIRMING", "READY_TO_CONFIRM" -> "CONFIRMING";
			case "WAITING_INPUT", "COLLECTING" -> "COLLECTING";
			case "WAITING_APPROVAL" -> "WAITING_APPROVAL";
			case "DONE", "COMPLETED" -> "DONE";
			case "ERROR", "FAILED" -> "ERROR";
			case "UNDERSTANDING" -> "UNDERSTANDING";
			default -> "EXECUTING";
		};
	}

	private String resolveResumeActionText(AgentResumeRequest request) {
		if (request == null || request.toolFeedbacks == null || request.toolFeedbacks.isEmpty()) {
			return "继续处理";
		}
		boolean hasRejected = request.toolFeedbacks.stream()
				.anyMatch(toolFeedback -> toolFeedback != null
						&& toolFeedback.getResult() != null
						&& "REJECTED".equalsIgnoreCase(toolFeedback.getResult().name()));
		if (hasRejected) {
			return "拒绝执行";
		}
		return "确认执行";
	}

	private AuthenticatedUserContext requireAuthenticatedUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
		}
		Object principal = authentication.getPrincipal();
		if (!(principal instanceof AuthenticatedUserContext authenticatedUser)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
		}
		return authenticatedUser;
	}

	private String requireAuthenticatedUserId(AuthenticatedUserContext authenticatedUser) {
		if (authenticatedUser == null || !StringUtils.hasText(authenticatedUser.userId())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
		}
		return authenticatedUser.userId();
	}

	private String resolveSystemCode(AuthenticatedUserContext authenticatedUser) {
		if (authenticatedUser != null && StringUtils.hasText(authenticatedUser.systemCode())) {
			return authenticatedUser.systemCode();
		}
		return defaultSystemCode;
	}

	// ── SSE helpers ─────────────────────────────────────────────────────

	private static ServerSentEvent<String> emptySse() {
		return ServerSentEvent.<String>builder().data("{}").build();
	}

	private static ServerSentEvent<String> errorSse(String message) {
		return ServerSentEvent.<String>builder()
				.data("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}").build();
	}

}







