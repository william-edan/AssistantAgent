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
package com.alibaba.assistant.agent.start.profile.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collections;

/**
 * 个人信息查询结果 DTO。
 *
 * <p>由于 DataAgent 返回的是 SSE 事件流，这里使用 Java 17 record
 * 对 AssistantAgent 侧的统一结果进行封装，并额外保留多条记录列表，
 * 供协议层按单条卡片或列表卡片输出。</p>
 *
 * @param name 查询姓名
 * @param summary 聚合后的摘要
 * @param rawText 首条记录或原始聚合文本
 * @param threadId DataAgent 线程 ID
 * @param records 结果集中的全部记录
 */
public record ProfileDTO(
        String name,
        String summary,
        String rawText,
        String threadId,
        List<Map<String, Object>> records) {

    /**
     * 兼容单条记录场景的构造方法。
     *
     * @param name 查询姓名
     * @param summary 摘要
     * @param rawText 原始文本
     * @param threadId 线程 ID
     */
    public ProfileDTO(String name, String summary, String rawText, String threadId) {
        this(name, summary, rawText, threadId, List.of());
    }

    /**
     * 对多条记录做不可变包装，避免后续映射阶段被意外修改。
     *
     * @param name 查询姓名
     * @param summary 摘要
     * @param rawText 原始文本
     * @param threadId 线程 ID
     * @param records 结果记录
     */
    public ProfileDTO {
        records = Optional.ofNullable(records)
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(record -> Collections.unmodifiableMap(new LinkedHashMap<>(record)))
                .toList();
    }

    /**
     * 判断当前是否包含多条记录。
     *
     * @return 多条记录时返回 true
     */
    public boolean hasMultipleRecords() {
        return records.size() > 1;
    }
}
