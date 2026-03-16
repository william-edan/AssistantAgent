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
 * ReAct 工具层。
 *
 * <p>这里放的是主 Agent 直接可见的核心编排工具，例如槽位收集、确认和执行。
 * 这几类工具决定了多轮对话如何采集参数、何时确认以及何时真正执行企业动作。</p>
 */
package com.alibaba.assistant.agent.runtime.tool.react;
