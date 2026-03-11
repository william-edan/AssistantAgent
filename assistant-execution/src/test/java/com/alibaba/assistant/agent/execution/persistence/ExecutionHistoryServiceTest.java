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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionHistoryServiceTest {

    @Test
    void shouldDelegateRunListFiltersToExecutionRunService() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ExecutionHistoryService service = new ExecutionHistoryService(executionRunService, executionStepService);

        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-2");
        run.setArtifactCode("oa.leave.apply");
        run.setArtifactType("WORKFLOW");
        run.setSpaceId(11L);
        run.setPlatformPrincipalId("u2002");
        run.setThreadId("THREAD-9");
        run.setStatus("COMPLETED");
        run.setStartedAt(LocalDateTime.of(2026, 3, 11, 12, 0));
        run.setCompletedAt(LocalDateTime.of(2026, 3, 11, 12, 3));
        when(executionRunService.listBySpace(11L, "RUN-2", "COMPLETED", "oa.leave.apply", "u2002", "THREAD-9", 5))
                .thenReturn(List.of(run));

        List<ExecutionHistoryRunSummaryView> views = service.listRuns(
                11L,
                "RUN-2",
                "COMPLETED",
                "oa.leave.apply",
                "u2002",
                "THREAD-9",
                5);

        assertEquals(1, views.size());
        assertEquals("RUN-2", views.get(0).runId());
        assertEquals("u2002", views.get(0).platformPrincipalId());
        assertEquals("THREAD-9", views.get(0).threadId());
    }
}
