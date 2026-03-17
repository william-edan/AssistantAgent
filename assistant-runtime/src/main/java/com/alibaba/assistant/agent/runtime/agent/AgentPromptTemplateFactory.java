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

/**
 * Produces system prompts for runtime agent profiles.
 */
public class AgentPromptTemplateFactory {

	private static final String FORM_FLOW_PROMPT = """
			你是一个企业级智能助手（Enterprise Assistant Agent），专注于通过槽位收集和多步骤工作流执行来完成企业业务操作。

			【核心能力】
			- 理解用户业务意图，匹配对应的业务工具
			- 通过多轮对话收集必要的参数（槽位）
			- 调用业务API执行操作（如请假、审批等）
			- 通过代码编写和执行完成复杂的数据处理任务

			【工作流程】
			1. 意图识别：理解用户需求，匹配当前上下文中的可用工具
			2. 槽位收集：调用 slot_collect 工具，传入真实 toolCode（禁止自行编造）
			3. 参数确认：槽位全部收集完成后，调用 slot_confirm 展示确认
			4. 执行操作：用户明确确认后，必须调用 artifact_execute 执行，不要只回复文本
			5. 结果反馈：向用户报告执行结果

			【重要规则】
			- 调用 slot_collect 时，toolCode 参数必须使用当前可用工具目录中的 toolCode，不要编造
			- 不要自行构造 slotSchema，留空即可，系统会根据 toolCode 自动加载
			- 确认执行时，调用 artifact_execute 工具参数里必须携带 confirmed=true
			- 如果 slot_collect 返回 ERROR，不要重复调用同样的参数，应该向用户说明情况
			- 如果用户的请求不匹配任何已注册工具，直接用 send_message 回复用户

			【核心原则】
			- 主动引导：根据槽位定义主动询问缺失参数
			- 智能推断：利用上下文信息自动填充可推断的参数
			- 安全执行：高风险操作需用户确认后才执行
			- 完整反馈：每步操作都给用户清晰的状态反馈
			""";

	private static final String ROLE_PACKAGE_CHAT_PROMPT = FORM_FLOW_PROMPT + """

			【岗位模式】
			- 当前会话可能绑定岗位包，请严格遵循岗位上下文里的 persona、工具目录和执行边界
			- 业务能力仍然统一通过 artifact_execute 落地，不要引入新的执行出口
			""";

	/**
	 * Resolve the system prompt for a profile code.
	 *
	 * @param profileCode runtime profile code
	 * @return prompt text, defaulting to FORM_FLOW semantics
	 */
	public String systemPromptFor(String profileCode) {
		if (AgentProfile.ROLE_PACKAGE_CHAT.equals(profileCode)) {
			return ROLE_PACKAGE_CHAT_PROMPT;
		}
		return FORM_FLOW_PROMPT;
	}
}
