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
package com.alibaba.assistant.agent.start.department.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 部门编制与变动查询结果 DTO。
 *
 * <p>用于承接 DataAgent 返回的部门维度统计结果，并保留完整记录列表，
 * 便于协议层动态拼装前端展示结构。</p>
 *
 * @param queryTitle 查询标题
 * @param summary 结果摘要
 * @param rawText 原始文本
 * @param threadId DataAgent 线程 ID
 * @param records 完整记录列表
 */
public record DepartmentDTO(
        String queryTitle,
        String summary,
        String rawText,
        String threadId,
        List<Map<String, Object>> records) {

    /**
     * 兼容单结果场景的构造方法。
     *
     * @param queryTitle 查询标题
     * @param summary 摘要
     * @param rawText 原始文本
     * @param threadId 线程 ID
     */
    public DepartmentDTO(String queryTitle, String summary, String rawText, String threadId) {
        this(queryTitle, summary, rawText, threadId, List.of());
    }

    /**
     * 对记录列表做不可变包装，避免后续流程误修改。
     *
     * @param queryTitle 查询标题
     * @param summary 摘要
     * @param rawText 原始文本
     * @param threadId 线程 ID
     * @param records 记录列表
     */
    public DepartmentDTO {
        records = Optional.ofNullable(records)
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(record -> Collections.unmodifiableMap(new LinkedHashMap<>(record)))
                .toList();
    }

    /**
     * 判断是否包含多条记录。
     *
     * @return 多条记录时返回 true
     */
    public boolean hasMultipleRecords() {
        return records.size() > 1;
    }
}
