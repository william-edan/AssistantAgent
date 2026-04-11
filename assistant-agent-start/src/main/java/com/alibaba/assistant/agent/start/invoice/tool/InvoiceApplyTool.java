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
 * 开票申请提交工具。
 *
 * <p>职责：复用 {@link InvoiceApplyAgentNode} 提交流程，统一输出 RESULT payload。</p>
 */
@Component
@Profile("migration")
public class InvoiceApplyTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "invoice_apply";

    private final InvoiceApplyAgentNode invoiceApplyAgentNode;

    public InvoiceApplyTool(ObjectMapper objectMapper, InvoiceApplyAgentNode invoiceApplyAgentNode) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.invoiceApplyAgentNode = invoiceApplyAgentNode;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        return objectMapper.writeValueAsString(invoiceApplyAgentNode.submit(args, toolContext));
    }

    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Submit an invoice application through tool_meta.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "amount": {"type": "string"},
                            "invoice_type": {"type": "string"},
                            "invoice_subject": {"type": "string"},
                            "types": {"type": "string"},
                            "invoice_title": {"type": "string"},
                            "invoice_tax": {"type": "string"},
                            "invoice_bank": {"type": "string"},
                            "invoice_account": {"type": "string"},
                            "invoice_banking": {"type": "string"},
                            "invoice_phone": {"type": "string"},
                            "invoice_address": {"type": "string"},
                            "project_name": {"type": "string"},
                            "project_id": {"type": "string"},
                            "remark": {"type": "string"},
                            "flow_id": {"type": "string"},
                            "check_uids": {"type": "string"},
                            "check_uames": {"type": "string"},
                            "check_copy_uids": {"type": "string"},
                            "check_copy_unames": {"type": "string"},
                            "confirmed": {"type": "boolean"},
                            "values": {"type": "object"},
                            "slotInputs": {"type": "object"},
                            "frontendThreadState": {"type": "object"}
                          }
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("invoice_apply_tools")
                .targetClassDescription("Invoice apply submit tools")
                .fewShots(List.of(new CodeExample(
                        "submit invoice apply",
                        "result = invoice_apply(amount='1000', invoice_type='1', invoice_subject='华东主体', types='1', invoice_title='腾讯科技', check_uids='6')",
                        "提交开票申请")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}
