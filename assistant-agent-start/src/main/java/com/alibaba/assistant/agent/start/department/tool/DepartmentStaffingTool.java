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
package com.alibaba.assistant.agent.start.department.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.department.dto.DepartmentDTO;
import com.alibaba.assistant.agent.start.department.intent.DepartmentIntentRecognizer;
import com.alibaba.assistant.agent.start.department.service.DepartmentHttpService;
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
 * 部门编制与变动查询工具。
 *
 * <p>当快速 Hook 命中后，会直接调用该 Tool，
 * 由该工具通过 HTTP 服务查询 DataAgent，并把结果返回给协议层。</p>
 */
@Component
@Profile("migration")
public class DepartmentStaffingTool extends AbstractDynamicCodeactTool {

    /**
     * 工具名称。
     */
    public static final String TOOL_NAME = "department_staffing_query";

    private final DepartmentIntentRecognizer intentRecognizer;

    private final DepartmentHttpService departmentHttpService;

    public DepartmentStaffingTool(
            ObjectMapper objectMapper,
            DepartmentIntentRecognizer intentRecognizer,
            DepartmentHttpService departmentHttpService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.intentRecognizer = intentRecognizer;
        this.departmentHttpService = departmentHttpService;
    }

    /**
     * 执行部门编制与变动查询。
     *
     * @param args 工具参数
     * @param toolContext 工具上下文
     * @return 查询结果 JSON
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

        DepartmentIntentRecognizer.RecognitionResult recognitionResult = intentRecognizer.recognize(userInput);
        if (!recognitionResult.matched()) {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "matched", false,
                    "message", "当前输入不属于部门编制与变动查询意图"));
        }

        try {
            DepartmentDTO departmentDTO = departmentHttpService.queryDepartmentStaffing(recognitionResult.originalInput())
                    .blockOptional()
                    .orElseThrow(() -> new IllegalStateException("未查询到部门编制与变动结果"));

            return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "matched", true,
                    "intent", recognitionResult.intentType().name(),
                    "query", recognitionResult.originalInput(),
                    "data", departmentDTO,
                    "message", departmentDTO.summary(),
                    "reply", departmentDTO.summary()));
        }
        catch (Exception exception) {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "matched", true,
                    "intent", recognitionResult.intentType().name(),
                    "query", recognitionResult.originalInput(),
                    "message", "部门编制与变动查询失败: " + exception.getMessage()));
        }
    }

    /**
     * 构建工具定义。
     *
     * @return 工具定义
     */
    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("查询各部门人员编制与变动情况的工具，命中后直接调用本地 DataAgent 流式搜索接口")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "userInput": {
                              "type": "string",
                              "description": "用户原始输入，例如：我要看一下今年各部门的人员编制和变动情况如何"
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
                .targetClassName("department_tools")
                .targetClassDescription("部门编制与变动查询工具集合")
                .fewShots(List.of(new CodeExample(
                        "query department staffing",
                        "result = department_staffing_query(userInput='我要看一下今年各部门的人员编制和变动情况如何')",
                        "返回部门编制与变动汇总信息")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}
