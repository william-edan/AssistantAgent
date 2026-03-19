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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormFlowExtractionContributorTest {

    @Test
    void shouldSkipWhenExtractionNotPending() {
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);

        FormFlowExtractionContributor contributor = new FormFlowExtractionContributor(adapter);

        assertFalse(contributor.shouldContribute(context(Map.of(
                AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application")))));
    }

    @Test
    void shouldRenderJsonOnlyExtractionContract() {
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);

        FormFlowExtractionContributor contributor = new FormFlowExtractionContributor(adapter);
        PromptContribution contribution = contributor.contribute(context(Map.of(
                AssistantStateKeys.FORM_FLOW_EXTRACTION_PENDING, true,
                AssistantStateKeys.MATCHED_TOOL_META, Map.of(
                        "toolCode", "gougu_oa.leave_application",
                        "toolName", "请假申请"))));

        String text = contribution.messagesToAppend().get(0).getText();
        assertTrue(text.contains("表单槽位提取任务"));
        assertTrue(text.contains("请假申请"));
        assertTrue(text.contains("gougu_oa.leave_application"));
        assertTrue(text.contains("displayMessage"));
        assertTrue(text.contains("extractedSlots"));
        assertTrue(text.contains("只输出 JSON"));
        assertTrue(text.contains("不要调用工具"));
    }

    private PromptContributorContext context(Map<String, Object> attrs) {
        return new PromptContributorContext() {
            @Override
            public java.util.List<Message> getMessages() {
                return Collections.emptyList();
            }

            @Override
            public Optional<SystemMessage> getSystemMessage() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> getAttributes() {
                return attrs;
            }

            @Override
            public Optional<String> getPhase() {
                return Optional.of("REACT");
            }
        };
    }
}
