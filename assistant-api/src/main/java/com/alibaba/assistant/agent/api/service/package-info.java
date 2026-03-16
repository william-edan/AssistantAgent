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
 * API 服务层。
 *
 * <p>负责把运行时事件、持久化读模型和前端协议连接起来，
 * 典型职责包括聊天记录落库、线程状态恢复、任务读侧聚合和异步任务回调投影。</p>
 */
package com.alibaba.assistant.agent.api.service;
