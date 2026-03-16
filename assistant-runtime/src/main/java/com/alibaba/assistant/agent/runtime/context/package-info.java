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
 * 运行时上下文层。
 *
 * <p>负责把线程状态、工具上下文和控制面配置解析成统一的运行时上下文，
 * 其中最关键的是空间、环境、用户身份等执行必需信息。</p>
 */
package com.alibaba.assistant.agent.runtime.context;
