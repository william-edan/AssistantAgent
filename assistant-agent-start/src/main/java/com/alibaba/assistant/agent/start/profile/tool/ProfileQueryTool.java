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
package com.alibaba.assistant.agent.start.profile.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.profile.dto.ProfileDTO;
import com.alibaba.assistant.agent.start.profile.intent.IntentRecognizer;
import com.alibaba.assistant.agent.start.profile.service.ProfileHttpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 个人档案查询工具。
 *
 * <p>该工具是 AssistantAgent 接入 DataAgent 的核心 Tool。
 * Hook 在命中后会直接构造本工具的调用，从而跳过模型生成。
 * 工具内部再进行一次意图校验，避免被误调用。</p>
 */
@Component
@Profile("migration")
public class ProfileQueryTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "profile_query";

    private final IntentRecognizer intentRecognizer;

    private final ProfileHttpService profileHttpService;

    public ProfileQueryTool(
            ObjectMapper objectMapper,
            IntentRecognizer intentRecognizer,
            ProfileHttpService profileHttpService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.intentRecognizer = intentRecognizer;
        this.profileHttpService = profileHttpService;
    }

    /**
     * 执行个人档案查询。
     *
     * @param args 工具参数
     * @param toolContext 工具上下文
     * @return 工具返回 JSON
     * @throws Exception 调用异常
     */
    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        String userInput = Optional.ofNullable(args.get("userInput"))
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .orElseGet(() -> Optional.ofNullable(args.get("query"))
                        .map(String::valueOf)
                        .orElse(""));
        IntentRecognizer.RecognitionResult recognitionResult = intentRecognizer.recognize(userInput);
        if (!recognitionResult.matched()) {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "matched", false,
                    "message", "当前输入不属于个人档案查询意图"));
        }
        try {
            ProfileDTO profileDTO = profileHttpService.queryProfile(recognitionResult.name()).blockOptional()
                    .orElseThrow(() -> new IllegalStateException("未查询到个人档案结果"));
            return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "matched", true,
                    "intent", recognitionResult.intentType().name(),
                    "name", recognitionResult.name(),
                    "data", profileDTO,
                    "message", profileDTO.summary(),
                    "reply", profileDTO.summary()));
        }
        catch (Exception exception) {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "matched", true,
                    "name", recognitionResult.name(),
                    "message", "个人档案查询失败: " + exception.getMessage()));
        }
    }

    /**
     * 构建 ToolDefinition。
     *
     * @return 工具定义
     */
    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("查询指定人员个人档案的工具，命中后直接调用本地 DataAgent 流式搜索接口")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "userInput": {
                              "type": "string",
                              "description": "用户原始输入，例如：张三的个人档案"
                            }
                          },
                          "required": ["userInput"]
                        }
                        """)
                .build();
    }

    /**
     * 构建工具元数据。
     *
     * @return 工具元数据
     */
    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("profile_tools")
                .targetClassDescription("个人档案查询工具集合")
                .fewShots(List.of(new CodeExample(
                        "query profile",
                        "result = profile_query(userInput='张三的个人档案')",
                        "返回张三的个人档案摘要")))
                .displayName("profile_query")
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}
