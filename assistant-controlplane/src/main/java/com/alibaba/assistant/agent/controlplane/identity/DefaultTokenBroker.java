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

import com.alibaba.assistant.agent.controlplane.identity.mapper.IdentityBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 默认的令牌代理实现。
 *
 * <p>当前会读取身份绑定表中的静态凭证并返回令牌租约，后续可以继续扩展为基于系统接入配置的动态换取模式。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class DefaultTokenBroker implements TokenBroker {

	private static final Logger logger = LoggerFactory.getLogger(DefaultTokenBroker.class);

	private static final int DEFAULT_TTL_SECONDS = 7200;

	private final IdentityBindingMapper identityBindingMapper;

	public DefaultTokenBroker(IdentityBindingMapper identityBindingMapper) {
		this.identityBindingMapper = identityBindingMapper;
	}

	@Override
	public Optional<TokenLease> acquire(String assistantUid, String systemCode) {
		IdentityBinding binding = identityBindingMapper.selectOne(
				new LambdaQueryWrapper<IdentityBinding>()
						.eq(IdentityBinding::getAssistantUid, assistantUid)
						.eq(IdentityBinding::getSystemCode, systemCode)
						.eq(IdentityBinding::getStatus, "active"));

		if (binding == null) {
			logger.debug("DefaultTokenBroker#acquire - no binding found: assistantUid={}, systemCode={}",
					assistantUid, systemCode);
			return Optional.empty();
		}

		// For now, use stored credentials directly as the token.
		// Future: call SystemAccessProfile.tokenEndpoint to exchange credentials for a real token.
		String leaseId = UUID.randomUUID().toString();
		TokenLease lease = new TokenLease(
				leaseId,
				binding.getCredentials(),
				systemCode,
				assistantUid,
				LocalDateTime.now().plusSeconds(DEFAULT_TTL_SECONDS));

		logger.debug("DefaultTokenBroker#acquire - lease created: leaseId={}, systemCode={}", leaseId, systemCode);
		return Optional.of(lease);
	}

	@Override
	public void revoke(String leaseId) {
		logger.debug("DefaultTokenBroker#revoke - leaseId={} (no-op for credential-based leases)", leaseId);
	}

}
