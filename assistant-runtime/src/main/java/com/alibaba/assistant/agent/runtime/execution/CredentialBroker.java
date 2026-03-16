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
package com.alibaba.assistant.agent.runtime.execution;

/**
 * 执行层凭证代理接口。
 *
 * <p>运行时在真正调用企业接口前，会通过该接口解析当前步骤所需的短期凭证租约，
 * 并产出已经标准化好的请求头、过期时间和租约标识。
 */
public interface CredentialBroker {

	/**
	 * 为当前执行请求解析短期凭证租约。
	 *
	 * @param request 执行步骤对应的凭证解析请求
	 * @return 解析完成后的凭证租约
	 */
	ResolvedCredentialLease resolve(CredentialResolutionRequest request);
}
