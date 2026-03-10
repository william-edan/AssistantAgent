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
package com.alibaba.assistant.agent.controlplane.workflow;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class WorkflowSpecServiceContractTest {

    @Test
    void shouldReturnEmptyWhenWorkflowCodeBlank() {
        WorkflowSpecService service = spy(new WorkflowSpecService());

        Optional<WorkflowSpec> result = service.findLatestEnabledByCode(1L, " ");

        assertTrue(result.isEmpty());
        verify(service, never()).getOne(any(Wrapper.class), eq(false));
    }

    @Test
    void shouldDelegateLatestEnabledLookupToGetOne() {
        WorkflowSpecService service = spy(new WorkflowSpecService());
        WorkflowSpec row = new WorkflowSpec();
        row.setWorkflowCode("oa.leave.apply");
        doReturn(row).when(service).getOne(any(Wrapper.class), eq(false));

        Optional<WorkflowSpec> result = service.findLatestEnabledByCode(1L, "oa.leave.apply");

        assertTrue(result.isPresent());
        assertEquals("oa.leave.apply", result.get().getWorkflowCode());
        verify(service).getOne(any(Wrapper.class), eq(false));
    }

}
