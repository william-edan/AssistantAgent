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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.controlplane.catalog.ControlPlaneCatalogOverview;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedAgentAppSummaryView;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedConnectorSummaryView;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedPlatformSpaceView;
import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaSummaryView;

import java.util.List;

/**
 * Catalog overview payload for operator-facing control-plane pages.
 */
public record ControlPlaneCatalogOverviewData(
        SpaceSummary space,
        List<ConnectorSummary> connectors,
        List<AgentAppSummary> agentApps,
        List<ToolSummary> tools) {

    public static ControlPlaneCatalogOverviewData from(ControlPlaneCatalogOverview overview) {
        return new ControlPlaneCatalogOverviewData(
                SpaceSummary.from(overview.space()),
                overview.connectors().stream().map(ConnectorSummary::from).toList(),
                overview.agentApps().stream().map(AgentAppSummary::from).toList(),
                overview.tools().stream().map(ToolSummary::from).toList());
    }

    public record SpaceSummary(Long spaceId, String spaceCode, String spaceName, String environment, String status) {
        static SpaceSummary from(ResolvedPlatformSpaceView view) {
            return new SpaceSummary(
                    view.spaceId(),
                    view.spaceCode(),
                    view.spaceName(),
                    normalize(view.environment()),
                    normalize(view.status()));
        }
    }

    public record ConnectorSummary(
            Long connectorId,
            String connectorCode,
            String systemCode,
            String displayName,
            String protocolType,
            String status,
            Integer version) {
        static ConnectorSummary from(ResolvedConnectorSummaryView view) {
            return new ConnectorSummary(
                    view.connectorId(),
                    view.connectorCode(),
                    view.systemCode(),
                    view.displayName(),
                    normalize(view.protocolType()),
                    normalize(view.status()),
                    view.version());
        }
    }

    public record AgentAppSummary(Long agentAppId, String agentAppCode, String displayName, String status) {
        static AgentAppSummary from(ResolvedAgentAppSummaryView view) {
            return new AgentAppSummary(
                    view.agentAppId(),
                    view.agentAppCode(),
                    view.displayName(),
                    normalize(view.status()));
        }
    }

    public record ToolSummary(
            Long toolId,
            String toolCode,
            String toolName,
            String systemCode,
            String toolType,
            String visibility,
            String invocationPolicy,
            String executionMode,
            String riskLevel,
            Boolean requiresConfirm,
            String status,
            Integer version) {
        static ToolSummary from(ResolvedToolMetaSummaryView view) {
            return new ToolSummary(
                    view.toolId(),
                    view.toolCode(),
                    view.toolName(),
                    view.systemCode(),
                    normalize(view.toolType()),
                    normalize(view.visibility()),
                    normalize(view.invocationPolicy()),
                    normalize(view.executionMode()),
                    normalize(view.riskLevel()),
                    view.requiresConfirm(),
                    normalize(view.status()),
                    view.version());
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
