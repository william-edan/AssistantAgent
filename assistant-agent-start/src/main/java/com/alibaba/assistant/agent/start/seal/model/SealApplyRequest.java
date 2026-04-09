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
package com.alibaba.assistant.agent.start.seal.model;

/**
 * 用章申请提交参数。
 *
 * <p>该记录对象与 `/adm/seal/add` 所需字段一一对应，
 * 由本地工作流整理完成后再交给 {@code tool_meta} 执行。</p>
 */
public record SealApplyRequest(
        String title,
        String did,
        String num,
        String useTime,
        String sealCateId,
        String isBorrow,
        String startTime,
        String endTime,
        String content,
        String file,
        String fileIds,
        String flowId,
        String checkUnames,
        String checkUids,
        String checkCopyUnames,
        String checkCopyUids) {
}
