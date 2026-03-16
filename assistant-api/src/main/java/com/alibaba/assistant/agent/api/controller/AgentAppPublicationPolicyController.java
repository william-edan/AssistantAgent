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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.controller.dto.AgentAppPublicationSourcePolicyData;
import com.alibaba.assistant.agent.api.controller.dto.AgentAppPublicationSourcePolicyRequest;
import com.alibaba.assistant.agent.api.controller.dto.AgentAppPublicationSourcePolicyResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationPolicyService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppPublicationSourcePolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * Agent 应用发布来源策略管理入口。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/agent-apps/{agentAppCode}/publication-source-policy")
public class AgentAppPublicationPolicyController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final AgentAppPublicationPolicyService publicationPolicyService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public AgentAppPublicationPolicyController(
            AgentAppPublicationPolicyService publicationPolicyService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.publicationPolicyService = publicationPolicyService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<AgentAppPublicationSourcePolicyResponse> getPublicationSourcePolicy(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requirePublicationPolicyAccess(authenticatedUser, spaceCode, normalizedEnvironment, agentAppCode);
        ResolvedAgentAppPublicationSourcePolicy resolved = publicationPolicyService
                .getPublicationSourcePolicy(spaceCode, normalizedEnvironment, agentAppCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "agent_app_not_found"));
        return ResponseEntity.ok(AgentAppPublicationSourcePolicyResponse.ok(toResponseData(resolved)));
    }

    @PutMapping
    public ResponseEntity<AgentAppPublicationSourcePolicyResponse> replacePublicationSourcePolicy(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) AgentAppPublicationSourcePolicyRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requirePublicationPolicyAccess(authenticatedUser, spaceCode, normalizedEnvironment, agentAppCode);
        AgentAppPublicationSourcePolicy policy = request == null
                ? AgentAppPublicationSourcePolicyRequest.empty().toPolicy()
                : request.toPolicy();
        ResolvedAgentAppPublicationSourcePolicy resolved = publicationPolicyService
                .replacePublicationSourcePolicy(spaceCode, normalizedEnvironment, agentAppCode, policy)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "agent_app_not_found"));
        return ResponseEntity.ok(AgentAppPublicationSourcePolicyResponse.ok(toResponseData(resolved)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requirePublicationPolicyAccess(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment,
            String agentAppCode) {
        if (!authorizationService.canManageAgentAppPublicationPolicy(authenticatedUser, spaceCode, environment, agentAppCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private AgentAppPublicationSourcePolicyData toResponseData(ResolvedAgentAppPublicationSourcePolicy resolved) {
        return AgentAppPublicationSourcePolicyData.from(resolved);
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
