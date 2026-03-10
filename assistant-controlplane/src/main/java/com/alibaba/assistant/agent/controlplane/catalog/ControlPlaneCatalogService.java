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
package com.alibaba.assistant.agent.controlplane.catalog;

import com.alibaba.assistant.agent.controlplane.action.ActionSpec;
import com.alibaba.assistant.agent.controlplane.action.ActionSpecService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpecService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Read facade for operator-facing space and catalog navigation.
 */
@Service
public class ControlPlaneCatalogService {

    private static final String DEFAULT_ENVIRONMENT = "prod";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ENABLED = "enabled";

    private final PlatformSpaceService platformSpaceService;
    private final ConnectorService connectorService;
    private final AgentAppService agentAppService;
    private final ActionSpecService actionSpecService;
    private final WorkflowSpecService workflowSpecService;

    public ControlPlaneCatalogService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService,
            AgentAppService agentAppService,
            ActionSpecService actionSpecService,
            WorkflowSpecService workflowSpecService) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
        this.agentAppService = agentAppService;
        this.actionSpecService = actionSpecService;
        this.workflowSpecService = workflowSpecService;
    }

    public List<ResolvedPlatformSpaceView> listSpaces(String environment, String keyword) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedKeyword = normalizeKeyword(keyword);
        return platformSpaceService.lambdaQuery()
                .eq(PlatformSpace::getEnvironment, normalizedEnvironment)
                .eq(PlatformSpace::getStatus, STATUS_ACTIVE)
                .and(StringUtils.hasText(normalizedKeyword), wrapper -> wrapper
                        .like(PlatformSpace::getSpaceCode, normalizedKeyword)
                        .or()
                        .like(PlatformSpace::getSpaceName, normalizedKeyword))
                .orderByAsc(PlatformSpace::getSpaceCode)
                .list()
                .stream()
                .map(space -> new ResolvedPlatformSpaceView(
                        space.getId(),
                        space.getSpaceCode(),
                        space.getSpaceName(),
                        space.getEnvironment(),
                        space.getStatus()))
                .toList();
    }

    public Optional<ControlPlaneCatalogOverview> getOverview(String spaceCode, String environment, String keyword) {
        if (!StringUtils.hasText(spaceCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedKeyword = normalizeKeyword(keyword);
        Optional<PlatformSpace> maybeSpace = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (maybeSpace.isEmpty()) {
            return Optional.empty();
        }
        PlatformSpace space = maybeSpace.get();
        Long spaceId = space.getId();
        return Optional.of(new ControlPlaneCatalogOverview(
                new ResolvedPlatformSpaceView(spaceId, space.getSpaceCode(), space.getSpaceName(), space.getEnvironment(), space.getStatus()),
                connectorService.lambdaQuery()
                        .eq(Connector::getSpaceId, spaceId)
                        .eq(Connector::getStatus, STATUS_ACTIVE)
                        .and(StringUtils.hasText(normalizedKeyword), wrapper -> wrapper
                                .like(Connector::getConnectorCode, normalizedKeyword)
                                .or()
                                .like(Connector::getDisplayName, normalizedKeyword)
                                .or()
                                .like(Connector::getSystemCode, normalizedKeyword))
                        .orderByAsc(Connector::getConnectorCode)
                        .list()
                        .stream()
                        .filter(connector -> matchesEnvironment(normalizedEnvironment, connector.getEnvironment()))
                        .map(connector -> new ResolvedConnectorSummaryView(
                                connector.getId(),
                                connector.getConnectorCode(),
                                connector.getSystemCode(),
                                connector.getDisplayName(),
                                connector.getProtocolType(),
                                connector.getStatus(),
                                connector.getVersion()))
                        .toList(),
                agentAppService.lambdaQuery()
                        .eq(AgentApp::getSpaceId, spaceId)
                        .eq(AgentApp::getStatus, STATUS_ACTIVE)
                        .and(StringUtils.hasText(normalizedKeyword), wrapper -> wrapper
                                .like(AgentApp::getAgentAppCode, normalizedKeyword)
                                .or()
                                .like(AgentApp::getDisplayName, normalizedKeyword))
                        .orderByAsc(AgentApp::getAgentAppCode)
                        .list()
                        .stream()
                        .map(app -> new ResolvedAgentAppSummaryView(
                                app.getId(),
                                app.getAgentAppCode(),
                                app.getDisplayName(),
                                app.getStatus()))
                        .toList(),
                actionSpecService.lambdaQuery()
                        .eq(ActionSpec::getSpaceId, spaceId)
                        .eq(ActionSpec::getStatus, STATUS_ENABLED)
                        .and(StringUtils.hasText(normalizedKeyword), wrapper -> wrapper
                                .like(ActionSpec::getActionCode, normalizedKeyword)
                                .or()
                                .like(ActionSpec::getRiskLevel, normalizedKeyword)
                                .or()
                                .like(ActionSpec::getSideEffectLevel, normalizedKeyword))
                        .orderByAsc(ActionSpec::getActionCode)
                        .list()
                        .stream()
                        .map(action -> new ResolvedActionSummaryView(
                                action.getId(),
                                action.getActionCode(),
                                action.getConnectorId(),
                                action.getRiskLevel(),
                                action.getSideEffectLevel(),
                                action.getStatus(),
                                action.getVersion()))
                        .toList(),
                workflowSpecService.lambdaQuery()
                        .eq(WorkflowSpec::getSpaceId, spaceId)
                        .eq(WorkflowSpec::getStatus, STATUS_ENABLED)
                        .and(StringUtils.hasText(normalizedKeyword), wrapper -> wrapper
                                .like(WorkflowSpec::getWorkflowCode, normalizedKeyword)
                                .or()
                                .like(WorkflowSpec::getDisplayName, normalizedKeyword))
                        .orderByAsc(WorkflowSpec::getWorkflowCode)
                        .list()
                        .stream()
                        .map(workflow -> new ResolvedWorkflowSummaryView(
                                workflow.getId(),
                                workflow.getWorkflowCode(),
                                workflow.getDisplayName(),
                                workflow.getInteractionSpecId(),
                                workflow.getStatus(),
                                workflow.getVersion()))
                        .toList()));
    }

    private boolean matchesEnvironment(String requestedEnvironment, String connectorEnvironment) {
        if (!StringUtils.hasText(connectorEnvironment)) {
            return true;
        }
        return connectorEnvironment.trim().equalsIgnoreCase(requestedEnvironment);
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalizeKeyword(String keyword) {
        return StringUtils.hasText(keyword) ? keyword.trim() : null;
    }
}
