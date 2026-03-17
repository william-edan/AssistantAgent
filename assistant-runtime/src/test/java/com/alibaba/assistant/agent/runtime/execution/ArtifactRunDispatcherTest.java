/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLeaseService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactRunDispatcherTest {

    @Test
    void shouldExecutePublishedArtifactAndMarkLeaseCompleted() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ProactiveRunLeaseService leaseService = mock(ProactiveRunLeaseService.class);
        ArtifactRunDispatcher dispatcher = new ArtifactRunDispatcher(
                lookupService,
                runtimeExecutor,
                leaseService,
                Runnable::run);
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:office1.approval_cleanup",
                "approval cleanup",
                null,
                null,
                false,
                "office1",
                artifact("office1.approval_cleanup"));
        ProactiveRunLease lease = new ProactiveRunLease();
        lease.setId(11L);
        when(lookupService.findPublishedArtifact(eq("office1.approval_cleanup"), any(ToolContext.class)))
                .thenReturn(Optional.of(descriptor));
        when(runtimeExecutor.execute(eq(descriptor), eq(Map.of("channel", "email")), any(ToolContext.class)))
                .thenReturn(Map.of("success", true, "runId", "run-1"));

        dispatcher.dispatch(
                "office1.approval_cleanup",
                Map.of("channel", "email"),
                Map.of("space_id", 10L, "agent_app_code", "finance-agent"),
                lease);

        verify(runtimeExecutor).execute(eq(descriptor), eq(Map.of("channel", "email")), any(ToolContext.class));
        verify(leaseService).markSucceeded(lease, "run-1");
    }

    private RuntimeArtifact artifact(String artifactCode) {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("approval_batch"));
        flowDefinition.setTerminal(List.of("approval_batch"));
        return new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "approval cleanup",
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                Map.of(),
                Map.of());
    }
}
