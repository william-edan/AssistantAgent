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
package com.alibaba.assistant.agent.controlplane.identity;

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

class PrincipalBindingV2ServiceContractTest {

    @Test
    void shouldReturnEmptyWhenPlatformPrincipalBlank() {
        PrincipalBindingV2Service service = spy(new PrincipalBindingV2Service());

        Optional<PrincipalBindingV2> result = service.findHighestPriorityActiveBinding(1L, 2L, " ");

        assertTrue(result.isEmpty());
        verify(service, never()).getOne(any(Wrapper.class), eq(false));
    }

    @Test
    void shouldDelegateHighestPriorityLookupToGetOne() {
        PrincipalBindingV2Service service = spy(new PrincipalBindingV2Service());
        PrincipalBindingV2 row = new PrincipalBindingV2();
        row.setTargetPrincipalId("erp-bot");
        doReturn(row).when(service).getOne(any(Wrapper.class), eq(false));

        Optional<PrincipalBindingV2> result = service.findHighestPriorityActiveBinding(1L, 2L, "u_1001");

        assertTrue(result.isPresent());
        assertEquals("erp-bot", result.get().getTargetPrincipalId());
        verify(service).getOne(any(Wrapper.class), eq(false));
    }

}
