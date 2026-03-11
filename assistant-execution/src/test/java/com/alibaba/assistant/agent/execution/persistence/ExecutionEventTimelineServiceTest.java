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
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionEventTimelineServiceTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void shouldFilterTimelineByStepEventTypeAndOccurredTime() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        LambdaQueryChainWrapper<AuditEvent> query = mock(LambdaQueryChainWrapper.class);
        ExecutionEventTimelineService service = new ExecutionEventTimelineService(executionRunService, auditEventService);

        LocalDateTime occurredAfter = LocalDateTime.of(2026, 3, 11, 12, 0);
        LocalDateTime occurredBefore = LocalDateTime.of(2026, 3, 11, 14, 0);
        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-1");
        run.setArtifactCode("oa.leave.apply");
        run.setArtifactType("WORKFLOW");
        run.setSpaceId(11L);

        AuditEvent event = new AuditEvent();
        event.setId(5L);
        event.setEventId("RUN-1:5");
        event.setRunId("RUN-1");
        event.setStepId("submit_approval");
        event.setEventType("STEP_WAITING_APPROVAL");
        event.setStatus("WAITING_APPROVAL");
        event.setToolName("oa.leave.apply");
        event.setToolOutput("{\"approvalRequestId\":\"RUN-1:submit_approval\"}");
        event.setCreatedAt(LocalDateTime.of(2026, 3, 11, 13, 0));

        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.of(run));
        when(auditEventService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), eq("RUN-1"))).thenReturn(query);
        when(query.eq(anyBoolean(), any(), eq("submit_approval"))).thenReturn(query);
        when(query.eq(anyBoolean(), any(), eq("STEP_WAITING_APPROVAL"))).thenReturn(query);
        when(query.ge(anyBoolean(), any(), eq(occurredAfter))).thenReturn(query);
        when(query.le(anyBoolean(), any(), eq(occurredBefore))).thenReturn(query);
        doReturn(query).when(query).orderByAsc(any(SFunction.class));
        when(query.list()).thenReturn(List.of(event));

        Optional<ExecutionEventTimelineView> timelineOptional = service.findTimeline(
                "RUN-1",
                "submit_approval",
                "STEP_WAITING_APPROVAL",
                occurredAfter,
                occurredBefore,
                5);

        assertTrue(timelineOptional.isPresent());
        ExecutionEventTimelineView timeline = timelineOptional.get();
        assertEquals("RUN-1", timeline.runId());
        assertEquals(1, timeline.events().size());
        assertEquals("STEP_WAITING_APPROVAL", timeline.events().get(0).eventType());
        assertEquals(LocalDateTime.of(2026, 3, 11, 13, 0), timeline.events().get(0).createdAt());
    }
}

