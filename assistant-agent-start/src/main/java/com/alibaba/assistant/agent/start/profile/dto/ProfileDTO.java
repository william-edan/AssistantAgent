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

/**
 * 个人档案查询结果 DTO。
 *
 * <p>由于 DataAgent 的真实接口返回的是 SSE 文本流，而不是固定 JSON 档案对象，
 * 因此这里使用 Java 17 record 对 AssistantAgent 侧的归一化结果进行封装。</p>
 *
 * @param name 查询姓名
 * @param summary 聚合后的档案摘要
 * @param rawText DataAgent 原始聚合文本
 * @param threadId DataAgent 返回的线程标识
 */
public record ProfileDTO(
        String name,
        String summary,
        String rawText,
        String threadId) {
}
