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

import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.agent.studio.dto.AgentResumeRequest;
import com.alibaba.cloud.ai.agent.studio.dto.AgentRunRequest;
import com.alibaba.cloud.ai.agent.studio.dto.messages.AgentRunResponse;
import com.alibaba.cloud.ai.agent.studio.dto.messages.MessageDTO;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise assistant chat endpoint.
 * Directly manages agent execution with streaming deduplication fix.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@CrossOrigin(
		origins = {"http://localhost:5173", "http://127.0.0.1:5173"},
		allowedHeaders = "*",
		methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@Profile("migration")
@RequestMapping("/api/chat")
public class ChatController {

	private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

	private final AgentLoader agentLoader;

	private final ObjectMapper mapper = new ObjectMapper();

	private final String defaultAppName;

	private final String defaultSystemCode;

	public ChatController(AgentLoader agentLoader,
			@Value("${assistant.chat.default-app-name:grayscale_agent}") String defaultAppName,
			@Value("${assistant.chat.default-system-code:}") String defaultSystemCode) {
		this.agentLoader = agentLoader;
		this.defaultAppName = defaultAppName;
		this.defaultSystemCode = defaultSystemCode;
	}

	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body cannot be null");
		}
		AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser();
		String effectiveSystemCode = resolveSystemCode(authenticatedUser);
		String effectiveAssistantUid = authenticatedUser.userId();

		Map<String, Object> stateDelta = new HashMap<>();
		if (StringUtils.hasText(effectiveSystemCode)) {
			stateDelta.put(AssistantStateKeys.SYSTEM_CODE, effectiveSystemCode);
		}
		if (StringUtils.hasText(effectiveAssistantUid)) {
			stateDelta.put(AssistantStateKeys.ASSISTANT_UID, effectiveAssistantUid);
		}

		return doRunSse(
				defaultAppName,
				authenticatedUser.userId(),
				request.getThreadId(),
				new UserMessageDTO(request.getMessage()),
				stateDelta.isEmpty() ? null : stateDelta);
	}

	/**
	 * Run endpoint aligned with studio AgentRunRequest.
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
	 * Resume endpoint aligned with studio AgentResumeRequest.
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

	// ── Core execution ──────────────────────────────────────────────────

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
		try {
			Agent agent = agentLoader.loadAgent(appName);
			RunnableConfig.Builder configBuilder = RunnableConfig.builder()
					.threadId(threadId)
					.addMetadata("user_id", userId);
			if (stateDelta != null && !stateDelta.isEmpty()) {
				configBuilder.addStateUpdate(stateDelta);
			}
			return executeAgent(newMessage.toUserMessage(), agent, configBuilder.build());
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
			Agent agent = agentLoader.loadAgent(request.appName);

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

			RunnableConfig.Builder configBuilder = RunnableConfig.builder()
					.threadId(request.threadId)
					.addMetadata("user_id", request.userId)
					.addHumanFeedback(metadataBuilder.build());
			if (request.stateDelta != null && !request.stateDelta.isEmpty()) {
				configBuilder.addStateUpdate(request.stateDelta);
			}
			return executeAgent(null, agent, configBuilder.build());
		}
		catch (Exception e) {
			logger.error("ChatController#doResumeSse - reason=agent恢复失败, threadId={}", request.threadId, e);
			return Flux.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent resume failed", e));
		}
	}

	/**
	 * Streams agent execution as SSE events.
	 * Fixes the upstream duplication bug: when the model finishes streaming,
	 * the graph emits an AGENT_MODEL_FINISHED event whose chunk contains the
	 * full accumulated text (already sent incrementally). Sending it again
	 * causes the frontend to display the message twice.
	 */
	private Flux<ServerSentEvent<String>> executeAgent(
			UserMessage userMessage, Agent agent, RunnableConfig runnableConfig)
			throws GraphRunnerException {
		Flux<NodeOutput> agentStream = userMessage != null
				? agent.stream(userMessage, runnableConfig)
				: agent.stream("", runnableConfig);
		ChunkDeduplicator deduplicator = new ChunkDeduplicator();

		return agentStream.map(nodeOutput -> {
					String node = nodeOutput.node();
					String agentName = nodeOutput.agent();
					Usage tokenUsage = nodeOutput.tokenUsage();

					AgentRunResponse agentResponse = null;
					if (nodeOutput instanceof StreamingOutput<?> streamingOutput) {
						Message message = streamingOutput.message();
						if (message == null) {
							return emptySse();
						}
						if (message instanceof AssistantMessage assistantMessage) {
							if (assistantMessage.hasToolCalls()) {
								agentResponse = new AgentRunResponse(
										node, agentName, assistantMessage, tokenUsage, "");
							}
							else {
								String chunk = deduplicator.nextChunk(
										streamingOutput.getOutputType(), assistantMessage.getText());
								agentResponse = new AgentRunResponse(
										node, agentName, assistantMessage, tokenUsage, chunk);
							}
						}
						else {
							agentResponse = new AgentRunResponse(
									node, agentName, message, tokenUsage, "");
						}
					}
					else if (nodeOutput instanceof InterruptionMetadata interruptionMetadata) {
						ToolRequestConfirmMessageDTO toolRequestMessage =
								MessageDTO.MessageDTOFactory.fromInterruptionMetadata(interruptionMetadata);
						agentResponse = new AgentRunResponse(
								node, agentName, toolRequestMessage, tokenUsage, "");
					}

					if (agentResponse != null) {
						try {
							return ServerSentEvent.<String>builder()
									.data(mapper.writeValueAsString(agentResponse))
									.build();
						}
						catch (Exception e) {
							logger.error("ChatController#executeAgent - reason=JSON序列化失败", e);
							return errorSse("Failed to serialize response");
						}
					}
					return emptySse();
				})
				.onErrorResume(error -> {
					logger.error("ChatController#executeAgent - reason=agent流执行出错", error);
					String errorMessage = error.getMessage() != null
							? error.getMessage() : "Unknown error occurred";
					String errorType = error.getClass().getSimpleName();
					String errorJson = String.format(
							"{\"error\":true,\"errorType\":\"%s\",\"errorMessage\":\"%s\"}",
							errorType.replace("\"", "\\\""),
							errorMessage.replace("\"", "\\\"").replace("\n", "\\n"));
					return Flux.just(ServerSentEvent.<String>builder()
							.event("error").data(errorJson).build());
				});
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
				userId,
				resolveSystemCode(authenticatedUser));
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
				request.userId,
				resolveSystemCode(authenticatedUser));
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
			Map<String, Object> baseStateDelta, String assistantUid, String systemCode) {
		Map<String, Object> merged = new LinkedHashMap<>();
		if (baseStateDelta != null) {
			merged.putAll(baseStateDelta);
		}
		if (StringUtils.hasText(assistantUid)) {
			merged.put(AssistantStateKeys.ASSISTANT_UID, assistantUid);
		}
		if (StringUtils.hasText(systemCode)) {
			merged.put(AssistantStateKeys.SYSTEM_CODE, systemCode);
		}
		return merged.isEmpty() ? null : merged;
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
