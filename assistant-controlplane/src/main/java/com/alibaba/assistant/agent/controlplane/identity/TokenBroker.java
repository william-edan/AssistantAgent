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
package com.alibaba.assistant.agent.controlplane.identity;

import java.util.Optional;

/**
 * 下游系统访问令牌代理接口。
 *
 * <p>运行时通过该接口代表平台用户向企业系统申请、复用或吊销短期访问令牌，
 * 以便执行请假、汇报、会议室预订等真实业务动作。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface TokenBroker {

	/**
	 * 为指定平台用户和目标系统获取可用的令牌租约。
	 *
	 * <p>实现可以直接返回尚未过期的缓存租约，也可以实时向企业系统重新申请。
	 *
	 * @param assistantUid 平台侧用户标识
	 * @param systemCode 目标系统编码
	 * @return 可用的令牌租约；若当前用户尚未完成绑定或无可用凭证则返回空
	 */
	Optional<TokenLease> acquire(String assistantUid, String systemCode);

	/**
	 * 吊销或失效化既有令牌租约。
	 *
	 * @param leaseId 要吊销的租约标识
	 */
	void revoke(String leaseId);

}
