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
package com.alibaba.assistant.agent.start.expense.model;

import java.util.List;

/**
 * 报销新增请求。
 *
 * @param subjectId 报销主体
 * @param code 凭证编号
 * @param expenseTime 报销日期
 * @param incomeMonth 所属月份
 * @param projectId 关联项目
 * @param applicantId 报销人 ID
 * @param applicantName 报销人名称
 * @param department 所属部门
 * @param checkUids 审批人
 * @param checkCopyUids 抄送人
 * @param details 报销明细
 */
public record ExpenseAddRequest(
        String subjectId,
        String code,
        String expenseTime,
        String incomeMonth,
        String projectId,
        String flowId,
        String applicantId,
        String applicantName,
        String department,
        String checkUnames,
        String checkUids,
        String checkCopyUnames,
        String checkCopyUids,
        List<ExpenseAddDetail> details) {
}
