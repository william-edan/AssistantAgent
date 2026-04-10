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

import java.math.BigDecimal;

/**
 * 报销明细。
 *
 * @param cateId 报销类型 ID
 * @param amount 金额
 * @param remarks 备注
 * @param expenseId 明细 ID，新增时默认为 0
 */
public record ExpenseAddDetail(
        String cateId,
        BigDecimal amount,
        String remarks,
        String expenseId) {
}
