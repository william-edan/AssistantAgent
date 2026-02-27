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
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.extension.dynamic.spi.DynamicToolFactoryContext;
import com.alibaba.assistant.agent.runtime.registry.TenantAwareToolRegistry;
import com.alibaba.assistant.agent.runtime.tool.codeact.CapabilityBridgeToolFactory;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enterprise assistant agent factory.
 * Creates a CodeactAgent configured for the slot-collection workflow.
 * Activated only under the "migration" profile to avoid Bean conflicts with CodeactAgentConfig.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Configuration
@Profile("migration")
public class AssistantAgentFactory {

	private static final Logger logger = LoggerFactory.getLogger(AssistantAgentFactory.class);

	private static final String SYSTEM_PROMPT_TEMPLATE = """
			你是一个企业级智能助手（Enterprise Assistant Agent），专注于通过槽位收集和多步骤工作流执行来完成企业业务操作。

			【核心能力】
			- 理解用户业务意图，匹配对应的业务工具
			- 通过多轮对话收集必要的参数（槽位）
			- 调用业务API执行操作（如请假、审批等）
			- 通过代码编写和执行完成复杂的数据处理任务

			【工作流程】
			1. 意图识别：理解用户需求，匹配下方【已注册的业务工具】中的工具
			2. 槽位收集：调用 slot_collect 工具，传入正确的 toolCode（必须使用下方列表中的真实 toolCode，禁止自行编造）
			3. 参数确认：槽位全部收集完成后，调用 slot_confirm 展示确认
			4. 执行操作：确认后由系统自动执行
			5. 结果反馈：向用户报告执行结果

			【重要规则】
			- 调用 slot_collect 时，toolCode 参数必须使用【已注册的业务工具】中列出的 toolCode，不要编造
			- 不要自行构造 slotSchema 或 requestSchema，留空即可，系统会根据 toolCode 自动加载
			- 如果 slot_collect 返回 ERROR，不要重复调用同样的参数，应该向用户说明情况
			- 如果用户的请求不匹配任何已注册工具，直接用 send_message 回复用户

			%s
			【核心原则】
			- 主动引导：根据槽位定义主动询问缺失参数
			- 智能推断：利用上下文信息自动填充可推断的参数
			- 安全执行：高风险操作需用户确认后才执行
			- 完整反馈：每步操作都给用户清晰的状态反馈
			""";

	@Autowired(required = false)
	private List<Hook> allHooks;

	@Bean
	public CodeactAgent assistantCodeactAgent(
			ChatModel chatModel,
			@Autowired(required = false) List<CodeactTool> codeactTools,
			@Autowired(required = false) CapabilityBridgeToolFactory capabilityBridgeToolFactory,
			@Autowired(required = false) TenantAwareToolRegistry tenantAwareToolRegistry,
			@Autowired(required = false) List<ToolCallback> reactToolCallbacks,
			@Autowired(required = false) List<Interceptor> interceptors,
			@Autowired(required = false) BaseCheckpointSaver checkpointSaver,
			@Autowired(required = false) ToolMetaService toolMetaService) {

		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=创建企业助手 CodeactAgent (migration profile)");

		List<CodeactTool> tools = codeactTools != null ? new ArrayList<>(codeactTools) : new ArrayList<>();
		if (tenantAwareToolRegistry == null && capabilityBridgeToolFactory != null) {
			List<CodeactTool> dynamicTools = capabilityBridgeToolFactory
					.createTools(DynamicToolFactoryContext.builder().build());
			tools.addAll(dynamicTools);
			logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=动态Capability工具加载完成, count={}",
					dynamicTools.size());
		}
		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=CodeactTool总数, count={}", tools.size());
		ToolCallback[] reactTools = reactToolCallbacks != null ? reactToolCallbacks.toArray(new ToolCallback[0])
				: new ToolCallback[0];
		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=React ToolCallback总数, count={}",
				reactTools.length);
		List<Interceptor> effectiveInterceptors =
				interceptors != null ? new ArrayList<>(interceptors) : new ArrayList<>();
		logger.info("AssistantAgentFactory#assistantCodeactAgent - reason=Interceptor总数, count={}",
				effectiveInterceptors.size());

		Map<AgentPhase, List<Hook>> hooksByPhase = HookPhaseUtils.groupByPhase(allHooks);
		List<Hook> reactHooks = hooksByPhase.get(AgentPhase.REACT);
		List<Hook> codeactHooks = hooksByPhase.get(AgentPhase.CODEACT);

		BaseCheckpointSaver saver = checkpointSaver != null ? checkpointSaver : new MemorySaver();

		String toolCatalog = buildToolCatalog(toolMetaService);
		String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, toolCatalog);

		CodeactAgent.CodeactAgentBuilder builder = CodeactAgent.builder()
				.name("AssistantAgent")
				.description("Enterprise assistant agent with slot-collection workflow")
				.systemPrompt(systemPrompt)
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
				.subAgentHooks(codeactHooks)
				.saver(saver);
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

	/**
	 * Build a tool catalog section for the system prompt from enabled ToolMeta records.
	 */
	private String buildToolCatalog(ToolMetaService toolMetaService) {
		if (toolMetaService == null) {
			logger.warn("AssistantAgentFactory#buildToolCatalog - reason=ToolMetaService不可用，跳过工具目录生成");
			return "";
		}
		try {
			LambdaQueryWrapper<ToolMeta> query = new LambdaQueryWrapper<>();
			query.and(w -> w.isNull(ToolMeta::getStatus).or().eq(ToolMeta::getStatus, "enabled"));
			query.orderByAsc(ToolMeta::getId);
			List<ToolMeta> metas = toolMetaService.list(query);

			if (metas == null || metas.isEmpty()) {
				logger.warn("AssistantAgentFactory#buildToolCatalog - reason=无可用的ToolMeta记录");
				return "";
			}

			StringBuilder sb = new StringBuilder("【已注册的业务工具】\n");
			sb.append("调用 slot_collect 时，toolCode 必须使用以下值之一：\n");
			for (ToolMeta meta : metas) {
				sb.append("- toolCode=\"").append(meta.getToolCode()).append("\"");
				if (meta.getToolName() != null) {
					sb.append(" (").append(meta.getToolName()).append(")");
				}
				if (meta.getDescription() != null) {
					sb.append("：").append(meta.getDescription());
				}
				if (meta.getSystemCode() != null) {
					sb.append(" [systemCode=").append(meta.getSystemCode()).append("]");
				}
				sb.append("\n");
			}
			sb.append("\n");

			logger.info("AssistantAgentFactory#buildToolCatalog - reason=工具目录生成完成, toolCount={}", metas.size());
			return sb.toString();
		}
		catch (Exception e) {
			logger.warn("AssistantAgentFactory#buildToolCatalog - reason=工具目录生成失败, error={}", e.getMessage());
			return "";
		}
	}

}
