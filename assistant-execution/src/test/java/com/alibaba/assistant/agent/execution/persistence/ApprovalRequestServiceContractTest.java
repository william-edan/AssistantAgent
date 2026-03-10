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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ApprovalRequestServiceContractTest {

    @Test
    void shouldFindLatestPendingApprovalRequestByRunAndStep() {
        ApprovalRequestService service = spy(new ApprovalRequestService());
        ApprovalRequest row = new ApprovalRequest();
        row.setRequestId("APR-1");
        doReturn(row).when(service).getOne(any(Wrapper.class), eq(false));

        Optional<ApprovalRequest> result = service.findLatestPendingByRunAndStep("RUN-1", "submit_approval");

        assertTrue(result.isPresent());
        assertEquals("APR-1", result.get().getRequestId());
        verify(service).getOne(any(Wrapper.class), eq(false));
    }
}
