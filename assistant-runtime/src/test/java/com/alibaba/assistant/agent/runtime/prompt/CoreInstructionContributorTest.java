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

import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreInstructionContributorTest {

    @Test
    void shouldSkipWhenDynamicPromptDisabled() {
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(false);

        CoreInstructionContributor contributor = new CoreInstructionContributor(adapter);

        assertFalse(contributor.shouldContribute(context()));
    }

    @Test
    void shouldIncludeCurrentTimeAnchorAndArtifactExecuteRule() {
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);

        CoreInstructionContributor contributor = new CoreInstructionContributor(adapter);
        PromptContribution contribution = contributor.contribute(context());

        String text = contribution.messagesToAppend().get(0).getText();
        assertTrue(text.contains("当前系统时间："));
        assertTrue(text.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*"));
        assertTrue(text.contains("今天/明天/后天"));
        assertTrue(text.contains("YYYY-MM-DD"));
        assertTrue(text.contains("个人事务"));
        assertTrue(text.contains("程序返回的“仍需补充参数”中的必填字段"));
        assertTrue(text.contains("不要重复追问"));
        assertTrue(text.contains("inferred_from"));
        assertTrue(text.contains("CONFIRMING"));
        assertTrue(text.contains("artifact_execute"));
        assertTrue(text.contains("confirmed=true"));
        assertTrue(text.contains("自由文本槽位"));
        assertTrue(text.contains("严禁虚构"));
        assertTrue(text.contains("本轮不得再次调用任何工具"));
    }

    private PromptContributorContext context() {
        return new PromptContributorContext() {
            @Override
            public List<Message> getMessages() {
                return Collections.emptyList();
            }

            @Override
            public Optional<SystemMessage> getSystemMessage() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Map.of();
            }

            @Override
            public Optional<String> getPhase() {
                return Optional.of("REACT");
            }
        };
    }

}

