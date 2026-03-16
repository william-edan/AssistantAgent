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
package com.alibaba.assistant.agent.api.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendMessageVisibilitySupportTest {

    @Test
    void shouldTreatInternalPlanningNarrationAsInvisible() {
        assertThat(FrontendMessageVisibilitySupport.isInternalPlanningNarration(
                "对应可用工具为 gougu_oa.work_report，接下来需通过 slot_collect 收集汇报类型。"))
                .isTrue();
        assertThat(FrontendMessageVisibilitySupport.isVisibleAssistantText(
                "对应可用工具为 gougu_oa.work_report，接下来需通过 slot_collect 收集汇报类型。"))
                .isFalse();
    }

    @Test
    void shouldFilterRealInternalPlanningNarrationVariant() {
        String narration = "用户明确表示“我要写汇报”，结合上下文，意图清晰对应工具 `gougu_oa.work_report`（工作汇报）。根据执行策略，操作型请求需先调收集必要参数。当前可用且匹配 `gougu_oa.work_report`。我将启动槽位收集流程，系统将自动加载该工具所需的 slotSchema，并识别当前缺失的必填字段。";

        assertThat(FrontendMessageVisibilitySupport.isInternalPlanningNarration(narration)).isTrue();
        assertThat(FrontendMessageVisibilitySupport.isVisibleAssistantText(narration)).isFalse();
    }

    @Test
    void shouldFilterMultilinePlanningNarrationFromLiveTranscript() {
        String narration = """
                用户意图是“写汇报”，匹配到可用工具：`gougu_oa.work_report`（工作汇报）。

                根据策略，操作型请求需先调用`slot_collect`收集必要参数。该工具对应工作汇报业务，系统将自动加载其槽位定义。

                我将使用 `artifact_execute`，传入真实toolCode `\"gougu_oa.work_report\"`，并基于上下文填充必要元信息。填入占位值
                """;

        assertThat(FrontendMessageVisibilitySupport.isInternalPlanningNarration(narration)).isTrue();
        assertThat(FrontendMessageVisibilitySupport.isVisibleAssistantText(narration)).isFalse();
    }

    @Test
    void shouldKeepBusinessReplyVisible() {
        assertThat(FrontendMessageVisibilitySupport.isInternalPlanningNarration(
                "请先选择汇报类型，我会自动带出本期时间范围。"))
                .isFalse();
        assertThat(FrontendMessageVisibilitySupport.isVisibleAssistantText(
                "请先选择汇报类型，我会自动带出本期时间范围。"))
                .isTrue();
    }
}
