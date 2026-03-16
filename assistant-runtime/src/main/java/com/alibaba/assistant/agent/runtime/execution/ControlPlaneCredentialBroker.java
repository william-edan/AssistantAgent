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

import com.alibaba.assistant.agent.controlplane.connector.AuthProfile;
import com.alibaba.assistant.agent.controlplane.connector.AuthProfileService;
import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.identity.PrincipalBinding;
import com.alibaba.assistant.agent.controlplane.identity.PrincipalBindingService;
import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 基于控制面配置解析执行凭证的代理实现。
 *
 * <p>该类会串联连接器、认证配置、主体绑定和系统令牌代理，
 * 把“平台用户 + 连接器”转换成真正可用于 HTTP 执行步骤的请求凭证。
 */
@Service
public class ControlPlaneCredentialBroker implements CredentialBroker {

    private final ConnectorService connectorService;

    private final AuthProfileService authProfileService;

    private final PrincipalBindingService principalBindingService;

    private final TokenBroker systemTokenBroker;

    public ControlPlaneCredentialBroker(
            ConnectorService connectorService,
            AuthProfileService authProfileService,
            PrincipalBindingService principalBindingService,
            TokenBroker systemTokenBroker) {
        this.connectorService = connectorService;
        this.authProfileService = authProfileService;
        this.principalBindingService = principalBindingService;
        this.systemTokenBroker = systemTokenBroker;
    }

    @Override
    public ResolvedCredentialLease resolve(CredentialResolutionRequest request) {
        Connector connector = requireConnector(request);
        AuthProfile authProfile = selectAuthProfile(request, authProfileService.listActiveByConnector(request.connectorId()));
        PrincipalBinding binding = principalBindingService
                .findHighestPriorityActiveBinding(request.spaceId(), request.connectorId(), request.platformPrincipalId())
                .orElseThrow(() -> new IllegalStateException("principal_binding_not_found"));

        String systemCode = normalize(connector.getSystemCode());
        OptionalCredential optionalCredential = resolveCredentialValue(request, authProfile, systemCode);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(resolveHeaderName(authProfile), resolveHeaderPrefix(authProfile) + optionalCredential.credentialValue());
        return new ResolvedCredentialLease(
                optionalCredential.leaseKey(),
                authProfile.getAuthProfileCode(),
                binding.getId(),
                connector.getId(),
                normalizeCredentialType(authProfile.getAuthType()),
                headers,
                optionalCredential.expiresAt(),
                normalize(connector.getBaseUrl()));
    }

    private Connector requireConnector(CredentialResolutionRequest request) {
        Connector connector = connectorService.getById(request.connectorId());
        if (connector == null || connector.getId() == null) {
            throw new IllegalStateException("connector_not_found");
        }
        if (!Objects.equals(connector.getSpaceId(), request.spaceId())) {
            throw new IllegalStateException("connector_space_mismatch");
        }
        if (StringUtils.hasText(connector.getStatus()) && !"active".equalsIgnoreCase(connector.getStatus().trim())) {
            throw new IllegalStateException("connector_inactive");
        }
        return connector;
    }

    private AuthProfile selectAuthProfile(CredentialResolutionRequest request, List<AuthProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("auth_profile_not_found");
        }
        if (!request.candidateAuthProfileCodes().isEmpty()) {
            for (String authProfileCode : request.candidateAuthProfileCodes()) {
                for (AuthProfile candidate : candidates) {
                    if (authProfileCode.equalsIgnoreCase(candidate.getAuthProfileCode())) {
                        return candidate;
                    }
                }
            }
            throw new IllegalStateException("auth_profile_candidate_not_found");
        }
        return candidates.get(0);
    }

    private OptionalCredential resolveCredentialValue(
            CredentialResolutionRequest request,
            AuthProfile authProfile,
            String systemCode) {
        if (StringUtils.hasText(authProfile.getCredentialRef())) {
            return new OptionalCredential(
                    buildLeaseKey(request, authProfile.getAuthProfileCode(), "credential_ref"),
                    authProfile.getCredentialRef().trim(),
                    Instant.now().plus(Duration.ofMinutes(15)));
        }
        if (StringUtils.hasText(systemCode) && systemTokenBroker != null) {
            TokenLease lease = systemTokenBroker.acquire(request.platformPrincipalId(), systemCode)
                    .orElseThrow(() -> new IllegalStateException("system_token_not_found"));
            return new OptionalCredential(
                    lease.leaseId(),
                    lease.accessToken(),
                    lease.expiresAt().atZone(ZoneId.systemDefault()).toInstant());
        }
        throw new IllegalStateException("credential_not_resolved");
    }

    private String resolveHeaderName(AuthProfile authProfile) {
        return StringUtils.hasText(authProfile.getTokenHeaderName())
                ? authProfile.getTokenHeaderName().trim()
                : "Authorization";
    }

    private String resolveHeaderPrefix(AuthProfile authProfile) {
        return authProfile.getTokenHeaderPrefix() == null ? "" : authProfile.getTokenHeaderPrefix();
    }

    private String normalizeCredentialType(String authType) {
        return StringUtils.hasText(authType)
                ? authType.trim().toUpperCase(Locale.ROOT)
                : "BEARER";
    }

    private String buildLeaseKey(CredentialResolutionRequest request, String authProfileCode, String suffix) {
        return request.runId() + ":" + request.stepId() + ":" + authProfileCode + ":" + suffix;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record OptionalCredential(String leaseKey, String credentialValue, Instant expiresAt) {
    }
}
