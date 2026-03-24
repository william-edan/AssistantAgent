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

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationUserInputResolverTest {

    @Test
    void shouldReturnNullWhenOnlyPersistedInputMatchesLastCollectedTurn() {
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "明天",
                "input", "明天",
                "messages", List.of(
                        AssistantMessage.builder().content("请补充结束日期").build(),
                        new UserMessage("明天"))));

        assertNull(ConversationUserInputResolver.resolve(state));
    }

    @Test
    void shouldPreferTrailingUserMessageWhenPersistedInputIsStale() {
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "我要请假",
                "input", "我要请假",
                "messages", List.of(
                        AssistantMessage.builder().content("请补充请假时间").build(),
                        new UserMessage("明天"))));

        assertEquals("明天", ConversationUserInputResolver.resolve(state));
    }

    @Test
    void shouldPreferCurrentTurnUserInputWhenPresent() {
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "明天",
                AssistantStateKeys.CURRENT_TURN_USER_INPUT, "明天",
                "input", "明天"));

        assertEquals("明天", ConversationUserInputResolver.resolve(state));
    }
}
