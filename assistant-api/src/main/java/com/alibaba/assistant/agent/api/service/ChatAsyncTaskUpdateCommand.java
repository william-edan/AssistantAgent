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
package com.alibaba.assistant.agent.api.service;

import java.util.Map;

/**
 * 异步任务更新命令，供内部长任务回调和本地模拟器复用。
 */
public record ChatAsyncTaskUpdateCommand(
        String threadId,
        String assistantUid,
        String appName,
        String systemCode,
        String turnId,
        Map<String, Object> task) {
}
