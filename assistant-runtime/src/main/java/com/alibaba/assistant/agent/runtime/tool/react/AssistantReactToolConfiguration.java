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
package com.alibaba.assistant.agent.runtime.tool.react;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.tool.ToolCallback;

/**
 * 迁移模式下的 React 工具注册配置。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Configuration
@Profile("migration")
public class AssistantReactToolConfiguration {

    @Bean
    public ToolCallback slotCollectToolCallback(SlotCollectTool slotCollectTool) {
        return SlotCollectTool.createToolCallback(slotCollectTool);
    }

    @Bean
    public ToolCallback slotConfirmToolCallback(SlotConfirmTool slotConfirmTool) {
        return SlotConfirmTool.createToolCallback(slotConfirmTool);
    }

    @Bean
    public ToolCallback artifactExecuteToolCallback(ArtifactExecuteTool artifactExecuteTool) {
        return ArtifactExecuteTool.createToolCallback(artifactExecuteTool);
    }
}
