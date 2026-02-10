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
package com.alibaba.assistant.agent.start.saas.config;

import com.alibaba.assistant.agent.start.saas.context.SaaSTenantContextHolder;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;

/**
 * Tenant handler for MyBatis-Plus tenant line interceptor.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SaaSTenantLineHandler implements TenantLineHandler {

    @Override
    public Expression getTenantId() {
        String tenantId = SaaSTenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        return new StringValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return false;
    }

    @Override
    public boolean ignoreInsert(java.util.List<Column> columns, String tenantIdColumn) {
        return columns != null && columns.stream()
                .map(Column::getColumnName)
                .anyMatch(name -> tenantIdColumn.equalsIgnoreCase(name));
    }
}
