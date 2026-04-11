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
package com.alibaba.assistant.agent.start.invoice.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.invoice.node.InvoiceApplyAgentNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 开票申请表单工具。
 *
 * <p>职责：复用 {@link InvoiceApplyAgentNode} 生成前端弹窗所需的表单结构。</p>
 */
@Component
@Profile("migration")
public class InvoiceApplyFormTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "invoice_apply_form";

    private final InvoiceApplyAgentNode invoiceApplyAgentNode;

    public InvoiceApplyFormTool(ObjectMapper objectMapper, InvoiceApplyAgentNode invoiceApplyAgentNode) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.invoiceApplyAgentNode = invoiceApplyAgentNode;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        return objectMapper.writeValueAsString(invoiceApplyAgentNode.buildForm(args, toolContext));
    }

    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Return the invoice-application form schema for frontend rendering.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "userInput": {"type": "string"},
                            "values": {"type": "object"},
                            "slotInputs": {"type": "object"}
                          }
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("invoice_apply_tools")
                .targetClassDescription("Invoice apply form tools")
                .fewShots(List.of(new CodeExample(
                        "open invoice apply form",
                        "result = invoice_apply_form(userInput='我要开票')",
                        "返回开票申请表单")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}
