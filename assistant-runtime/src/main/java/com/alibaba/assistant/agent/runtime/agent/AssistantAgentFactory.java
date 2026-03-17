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
package com.alibaba.assistant.agent.runtime.agent;

import com.alibaba.assistant.agent.autoconfigure.CodeactAgent;
import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhaseUtils;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.extension.experience.hook.FastIntentReactHook;
import com.alibaba.assistant.agent.extension.dynamic.spi.DynamicToolFactoryContext;
import com.alibaba.assistant.agent.runtime.config.NullSafeStateSerializer;
import com.alibaba.assistant.agent.runtime.interceptor.AuditToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.HumanInTheLoopToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.IdentityEnricherToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.InternalPromptContributionToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.PolicyCheckModelInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.PolicyGuardToolInterceptor;
import com.alibaba.assistant.agent.runtime.intent.AssistantFastIntentHook;
import com.alibaba.assistant.agent.runtime.registry.TenantAwareToolRegistry;
import com.alibaba.assistant.agent.runtime.tool.react.AssistantReactToolConfiguration;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 企业助手 Agent 装配工厂。
 *
 * <p>负责在 migration 模式下创建真正对外服务的 CodeactAgent，
 * 并装配系统提示词、可见工具、拦截器、Hook 和检查点能力。
 * 这里定义了系统默认的对话主链：意图识别、槽位收集、确认、执行和结果反馈。</p>
 */
@Configuration
@Profile("migration")
public class AssistantAgentFactory {

	private static final Logger logger = LoggerFactory.getLogger(AssistantAgentFactory.class);

	@Autowired(required = false)
	private List<Hook> allHooks;

	@Bean
	public CodeactAgent assistantCodeactAgent(
			ChatModel chatModel,
			@Autowired(required = false) List<CodeactTool> codeactTools,
			@Autowired(required = false) TenantAwareToolRegistry tenantAwareToolRegistry,
			@Autowired(required = false) List<ToolCallback> reactToolCallbacks,
			@Autowired(required = false) List<Interceptor> interceptors,
			@Autowired(required = false) BaseCheckpointSaver checkpointSaver) {

		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=创建企业助手 CodeactAgent (migration profile)");

		AgentPromptTemplateFactory promptTemplateFactory = new AgentPromptTemplateFactory();
		AgentProfileResolver agentProfileResolver = new AgentProfileResolver(promptTemplateFactory);
		AgentProfile profile = agentProfileResolver.resolve(Map.of());

		List<CodeactTool> tools = codeactTools != null ? new ArrayList<>(codeactTools) : new ArrayList<>();
		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=CodeactTool总数, count={}", tools.size());
		List<CodeactTool> reactAccessibleCodeactTools =
				collectReactAccessibleCodeactTools(profile, tools, tenantAwareToolRegistry);
		List<ToolCallback> filteredReactCallbacks =
				filterReactToolCallbacks(profile, reactToolCallbacks, tenantAwareToolRegistry);
		ToolCallback[] reactTools = mergeReactAndCodeactToolCallbacks(filteredReactCallbacks, reactAccessibleCodeactTools);
		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=React ToolCallback总数, count={}",
				reactTools.length);
		List<Interceptor> effectiveInterceptors =
				interceptors != null ? new ArrayList<>(interceptors) : new ArrayList<>();
		effectiveInterceptors = orderInterceptors(effectiveInterceptors);
		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=Interceptor总数, count={}",
				effectiveInterceptors.size());

		Map<AgentPhase, List<Hook>> hooksByPhase = HookPhaseUtils.groupByPhase(allHooks);
		List<Hook> reactHooks = filterReactHooks(hooksByPhase.get(AgentPhase.REACT));
		List<Hook> codeactHooks = hooksByPhase.get(AgentPhase.CODEACT);

		HumanInTheLoopHook humanInTheLoopHook = buildHumanInTheLoopHook(reactTools);
		if (humanInTheLoopHook != null) {
			reactHooks.add(humanInTheLoopHook);
			logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=HumanInTheLoopHook已注册");
		}

		BaseCheckpointSaver saver = checkpointSaver != null ? checkpointSaver : new MemorySaver();

		// Build CompileConfig with checkpoint saver.
		// HumanInTheLoopHook implements InterruptableAction — the framework's NodeExecutor
		// calls interrupt() on ANY node implementing InterruptableAction, regardless of
		// CompileConfig interruptBefore/After settings. Do NOT add interruptAfter here:
		// it would cause a double-interrupt on resume (once from interrupt() returning
		// empty after feedback validation, then again from MainGraphExecutor.shouldInterrupt()).
		CompileConfig.Builder compileConfigBuilder = CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(saver).build())
				.recursionLimit(Integer.MAX_VALUE);

		CodeactAgent.CodeactAgentBuilder builder = CodeactAgent.builder()
				.name("AssistantAgent")
				.description("Enterprise assistant agent with slot-collection workflow")
				.systemPrompt(profile.systemPrompt())
				.model(chatModel)
				.codingChatModel(chatModel)
				.language(Language.PYTHON)
				.enableInitialCodeGen(false)
				.allowIO(false)
				.allowNativeAccess(false)
				.executionTimeout(30000)
				.tools(reactTools)
				.codeactTools(tools)
				.hooks(reactHooks)
				.subAgentHooks(codeactHooks);
		builder.compileConfig(compileConfigBuilder.build());
		builder.stateSerializer(new NullSafeStateSerializer(OverAllState::new));
		if (tenantAwareToolRegistry != null) {
			builder.codeactToolRegistry(tenantAwareToolRegistry);
			logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=启用TenantAwareToolRegistry");
		}
		if (!effectiveInterceptors.isEmpty()) {
			builder.interceptors(effectiveInterceptors);
		}

		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=企业助手 CodeactAgent 构建完成");
		return builder.build();
	}

	static ToolCallback[] mergeReactAndCodeactToolCallbacks(List<ToolCallback> reactToolCallbacks,
			List<CodeactTool> codeactTools) {
		Map<String, ToolCallback> merged = new LinkedHashMap<>();
		mergeToolCallbacks(merged, reactToolCallbacks);
		mergeToolCallbacks(merged, codeactTools);
		return merged.values().toArray(new ToolCallback[0]);
	}

	static List<ToolCallback> filterReactToolCallbacks(
			List<ToolCallback> reactToolCallbacks,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		return filterReactToolCallbacks(defaultFormFlowProfile(), reactToolCallbacks, tenantAwareToolRegistry);
	}

	static List<ToolCallback> filterReactToolCallbacks(
			AgentProfile profile,
			List<ToolCallback> reactToolCallbacks,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		if (reactToolCallbacks == null || reactToolCallbacks.isEmpty()) {
			return List.of();
		}
		List<ToolCallback> filtered = new AgentToolExposurePolicy()
				.filterReactToolCallbacks(profile, reactToolCallbacks, tenantAwareToolRegistry);
		Set<String> blockedToolNames = new LinkedHashSet<>();
		for (ToolCallback callback : reactToolCallbacks) {
			if (callback == null || callback.getToolDefinition() == null) {
				continue;
			}
			String toolName = callback.getToolDefinition().name();
			if (!filtered.contains(callback)) {
				if (StringUtils.hasText(toolName)) {
					blockedToolNames.add(toolName);
				}
			}
		}
		if (!blockedToolNames.isEmpty()) {
			logger.info("AssistantAgentFactory#filterReactToolCallbacks - reason=过滤内部工具回调, toolNames={}",
					blockedToolNames);
		}
		return List.copyOf(filtered);
	}

	static List<CodeactTool> collectReactAccessibleCodeactTools(List<CodeactTool> codeactTools,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		return collectReactAccessibleCodeactTools(defaultFormFlowProfile(), codeactTools, tenantAwareToolRegistry);
	}

	static List<CodeactTool> collectReactAccessibleCodeactTools(
			AgentProfile profile,
			List<CodeactTool> codeactTools,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		return new AgentToolExposurePolicy()
				.collectReactAccessibleCodeactTools(profile, codeactTools, tenantAwareToolRegistry);
	}

	private static void mergeToolCallbacks(Map<String, ToolCallback> merged, List<? extends ToolCallback> source) {
		if (source == null || source.isEmpty()) {
			return;
		}
		for (ToolCallback callback : source) {
			if (callback == null || callback.getToolDefinition() == null) {
				continue;
			}
			String toolName = callback.getToolDefinition().name();
			if (!StringUtils.hasText(toolName)) {
				continue;
			}
			merged.putIfAbsent(toolName, callback);
		}
	}

	static List<Interceptor> orderInterceptors(List<Interceptor> interceptors) {
		if (interceptors == null || interceptors.isEmpty()) {
			return new ArrayList<>();
		}
		List<Interceptor> ordered = new ArrayList<>(interceptors);
		ordered.sort(Comparator
				.comparingInt(AssistantAgentFactory::interceptorOrder)
				.thenComparing(interceptor -> interceptor.getClass().getName()));
		return ordered;
	}

	private static int interceptorOrder(Interceptor interceptor) {
		if (interceptor == null) {
			return 50;
		}
		if (interceptor instanceof PolicyCheckModelInterceptor) {
			return 0;
		}
		if (interceptor instanceof InternalPromptContributionToolInterceptor) {
			return 5;
		}
		if (interceptor instanceof IdentityEnricherToolInterceptor) {
			return 10;
		}
		if (interceptor instanceof PolicyGuardToolInterceptor) {
			return 20;
		}
		if (interceptor instanceof HumanInTheLoopToolInterceptor) {
			return 30;
		}
		if (interceptor instanceof AuditToolInterceptor) {
			return 90;
		}
		return 50;
	}

	static HumanInTheLoopHook buildHumanInTheLoopHook(ToolCallback[] reactTools) {
		if (reactTools == null || reactTools.length == 0) {
			return null;
		}
		HumanInTheLoopHook.Builder builder = HumanInTheLoopHook.builder();
		int count = 0;
		for (ToolCallback tool : reactTools) {
			if (tool == null || tool.getToolDefinition() == null) {
				continue;
			}
			String name = tool.getToolDefinition().name();
			if (!StringUtils.hasText(name)) {
				continue;
			}
			String normalized = name.trim().toLowerCase(Locale.ROOT);
			if ("artifact_execute".equals(normalized)) {
				builder.approvalOn(name, ToolConfig.builder()
						.description("需要用户确认后执行")
						.build());
				count++;
				logger.info("AssistantAgentFactory#buildHumanInTheLoopHook - reason=注册execute工具, toolName={}", name);
			}
		}
		if (count == 0) {
			return null;
		}
		return builder.build();
	}

	static List<Hook> filterReactHooks(List<Hook> hooks) {
		if (hooks == null || hooks.isEmpty()) {
			return new ArrayList<>();
		}
		List<Hook> source = new ArrayList<>(hooks);
		boolean containsRuntimeFastIntent = source.stream()
				.anyMatch(hook -> hook instanceof AssistantFastIntentHook);
		if (!containsRuntimeFastIntent) {
			return source;
		}
		List<Hook> filtered = new ArrayList<>();
		for (Hook hook : source) {
			if (hook instanceof FastIntentReactHook) {
				logger.info("AssistantAgentFactory#filterReactHooks - reason=skip disabled FastIntentReactHook");
				continue;
			}
			filtered.add(hook);
		}
		return filtered;
	}

	private static AgentProfile defaultFormFlowProfile() {
		AgentPromptTemplateFactory promptTemplateFactory = new AgentPromptTemplateFactory();
		return new AgentProfile(
				AgentProfile.FORM_FLOW,
				promptTemplateFactory.systemPromptFor(AgentProfile.FORM_FLOW),
				AssistantReactToolConfiguration.builtInReactToolNames());
	}

}






