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

import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLeaseService;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Asynchronously dispatches a leased proactive run into the canonical artifact runtime.
 */
@Component
public class ArtifactRunDispatcher {

    private final ArtifactPublicationLookupService artifactPublicationLookupService;

    private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

    private final ProactiveRunLeaseService proactiveRunLeaseService;

    @Nullable
    private final Executor executionExecutor;

    public ArtifactRunDispatcher(
            ArtifactPublicationLookupService artifactPublicationLookupService,
            ArtifactRuntimeExecutor artifactRuntimeExecutor,
            ProactiveRunLeaseService proactiveRunLeaseService,
            @Nullable @Qualifier("migrationExecutionExecutor") Executor executionExecutor) {
        this.artifactPublicationLookupService = artifactPublicationLookupService;
        this.artifactRuntimeExecutor = artifactRuntimeExecutor;
        this.proactiveRunLeaseService = proactiveRunLeaseService;
        this.executionExecutor = executionExecutor;
    }

    public void dispatch(
            String artifactCode,
            @Nullable Map<String, Object> arguments,
            @Nullable Map<String, Object> contextAttributes,
            ProactiveRunLease lease) {
        Runnable task = () -> executeDispatch(artifactCode, arguments, contextAttributes, lease);
        if (executionExecutor != null) {
            executionExecutor.execute(task);
            return;
        }
        CompletableFuture.runAsync(task);
    }

    private void executeDispatch(
            String artifactCode,
            @Nullable Map<String, Object> arguments,
            @Nullable Map<String, Object> contextAttributes,
            ProactiveRunLease lease) {
        Map<String, Object> effectiveContext = contextAttributes != null
                ? new LinkedHashMap<>(contextAttributes)
                : new LinkedHashMap<>();
        ToolContext toolContext = new ToolContext(effectiveContext);
        Optional<PublishedToolDescriptor> descriptor = artifactPublicationLookupService.findPublishedArtifact(artifactCode, toolContext);
        if (descriptor.isEmpty()) {
            proactiveRunLeaseService.markFailed(lease, "Published artifact not found: " + artifactCode);
            return;
        }
        try {
            Map<String, Object> result = artifactRuntimeExecutor.execute(
                    descriptor.get(),
                    arguments != null ? new LinkedHashMap<>(arguments) : Map.of(),
                    toolContext);
            if (Boolean.FALSE.equals(result.get("success")) || StringUtils.hasText(asText(result.get("error")))) {
                proactiveRunLeaseService.markFailed(lease, firstNonBlank(asText(result.get("error")), "artifact_run_failed"));
                return;
            }
            proactiveRunLeaseService.markSucceeded(lease, asText(result.get("runId")));
        }
        catch (Exception ex) {
            proactiveRunLeaseService.markFailed(lease, firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName()));
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
