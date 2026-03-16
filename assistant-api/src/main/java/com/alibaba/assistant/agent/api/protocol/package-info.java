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

/**
 * 前端协议层。
 *
 * <p>负责把内部工具输出、执行事件和状态快照统一转换为前端稳定协议，
 * 例如 {@code STAGE}、{@code FORM_STATE}、{@code TASK_STATE} 和 {@code RESULT}。</p>
 */
package com.alibaba.assistant.agent.api.protocol;
