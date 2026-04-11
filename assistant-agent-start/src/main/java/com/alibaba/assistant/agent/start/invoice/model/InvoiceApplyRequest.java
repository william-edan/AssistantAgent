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
package com.alibaba.assistant.agent.start.invoice.model;

/**
 * 开票申请提交请求。
 *
 * <p>字段命名严格对齐业务接口 {@code /finance/invoice/add}，避免在工具层和接口层之间反复转换。</p>
 */
public record InvoiceApplyRequest(
        String amount,
        String invoiceType,
        String invoiceSubject,
        String types,
        String invoiceTitle,
        String invoiceTax,
        String invoiceBank,
        String invoiceAccount,
        String invoiceBanking,
        String invoicePhone,
        String invoiceAddress,
        String projectName,
        String projectId,
        String remark,
        String flowId,
        String checkUids,
        String checkUames,
        String checkCopyUids,
        String checkCopyUnames) {
}
