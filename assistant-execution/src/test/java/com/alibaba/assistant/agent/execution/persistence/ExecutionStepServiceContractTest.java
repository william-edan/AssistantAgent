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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ExecutionStepServiceContractTest {

    @Test
    void shouldListExecutionStepsByRunId() {
        ExecutionStepService service = spy(new ExecutionStepService());
        ExecutionStep row = new ExecutionStep();
        row.setStepId("create_leave");
        doReturn(List.of(row)).when(service).list(any(Wrapper.class));

        List<ExecutionStep> result = service.listByRunId("RUN-1");

        assertEquals(1, result.size());
        assertEquals("create_leave", result.get(0).getStepId());
        verify(service).list(any(Wrapper.class));
    }
}
