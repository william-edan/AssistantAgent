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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Provides baseline orchestration instructions for query/operation split.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class CoreInstructionContributor implements PromptContributor {

	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final RuntimeConfigView runtimeConfigView;

	public CoreInstructionContributor(RuntimeConfigView runtimeConfigView) {
		this.runtimeConfigView = runtimeConfigView;
	}

	@Override
	public String getName() {
		return "core-instruction";
	}

	@Override
	public int getPriority() {
		return 100;
	}

	@Override
	public boolean shouldContribute(PromptContributorContext context) {
		return runtimeConfigView.promptDynamicEnabled();
	}

	@Override
	public PromptContribution contribute(PromptContributorContext context) {
		String now = LocalDateTime.now().format(TIME_FORMATTER);
		String text = """
				【时间锚点】
				当前系统时间：%s
				如果用户提到“今天/明天/后天”等相对日期，必须先基于该时间锚点换算为 YYYY-MM-DD 再填槽位。

				【执行策略补充】
				1. 查询型请求优先调用 search/reply，不要进入 slot_collect。
				2. 操作型请求先调用 slot_collect，缺参时追问，完整后再 slot_confirm。
				3. 工具参数必须来源于上下文可用信息与用户输入，不可编造 toolCode。
				4. 高风险操作必须先确认再执行。
				5. 请假场景中，若用户语义为“有事/私事/个人原因”，可将 reason 归一为“个人事务”。
				6. 追问范围必须严格限定为程序返回的“仍需补充参数”中的必填字段。
				7. 对已识别参数、默认值可用字段、计算字段、askMode=AUTO 字段、inferred_from 可推断字段，不要重复追问。
				8. 当阶段为 CONFIRMING 且用户明确确认时，必须调用 artifact_execute，并携带正确的 toolCode，不要只做文本确认。
				9. 调用 artifact_execute 时必须携带 confirmed=true。
				10. 对 works/reason/title 等自由文本槽位：若当前用户输入未提供内容且已识别参数中不存在该字段，严禁虚构或示例化填写，必须明确追问。
				11. 当你本轮已向用户发出缺失参数追问后，本轮不得再次调用任何工具；必须等待用户下一条输入再继续。
				""".formatted(now);
		return PromptContribution.builder()
				.append(new UserMessage(text))
				.build();
	}

}



