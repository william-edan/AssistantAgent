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
package com.alibaba.assistant.agent.execution.persistence;

import com.alibaba.assistant.agent.controlplane.audit.AuditEvent;
import com.alibaba.assistant.agent.controlplane.audit.AuditEventService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read facade for persisted execution event timelines.
 */
@Service
public class ExecutionEventTimelineService {

    private static final int DEFAULT_LIMIT = 50;

    private final ExecutionRunService executionRunService;

    private final AuditEventService auditEventService;

    public ExecutionEventTimelineService(
            ExecutionRunService executionRunService,
            AuditEventService auditEventService) {
        this.executionRunService = executionRunService;
        this.auditEventService = auditEventService;
    }

    /**
     * Load persisted execution events for a run with optional step, event type, and time filtering.
     */
    public Optional<ExecutionEventTimelineView> findTimeline(
            String runId,
            String stepId,
            String eventType,
            LocalDateTime occurredAfter,
            LocalDateTime occurredBefore,
            Integer limit) {
        if (!StringUtils.hasText(runId)) {
            return Optional.empty();
        }
        String normalizedRunId = runId.trim();
        String normalizedStepId = StringUtils.hasText(stepId) ? stepId.trim() : null;
        String normalizedEventType = StringUtils.hasText(eventType) ? eventType.trim() : null;
        int normalizedLimit = normalizeLimit(limit);
        return executionRunService.findLatestByRunId(normalizedRunId)
                .map(run -> new ExecutionEventTimelineView(
                        run.getRunId(),
                        run.getArtifactCode(),
                        run.getArtifactType(),
                        run.getSpaceId(),
                        auditEventService.lambdaQuery()
                                .eq(AuditEvent::getRunId, normalizedRunId)
                                .eq(normalizedStepId != null, AuditEvent::getStepId, normalizedStepId)
                                .eq(normalizedEventType != null, AuditEvent::getEventType, normalizedEventType)
                                .ge(occurredAfter != null, AuditEvent::getCreatedAt, occurredAfter)
                                .le(occurredBefore != null, AuditEvent::getCreatedAt, occurredBefore)
                                .orderByAsc(AuditEvent::getCreatedAt)
                                .orderByAsc(AuditEvent::getId)
                                .list()
                                .stream()
                                .limit(normalizedLimit)
                                .map(event -> new ExecutionEventTimelineItemView(
                                        event.getEventId(),
                                        event.getRunId(),
                                        event.getStepId(),
                                        event.getEventType(),
                                        event.getStatus(),
                                        event.getToolName(),
                                        event.getErrorMessage(),
                                        event.getToolOutput(),
                                        event.getCreatedAt()))
                                .toList()));
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 200);
    }
}

