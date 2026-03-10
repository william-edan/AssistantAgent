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
package com.alibaba.assistant.agent.controlplane.space;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;

class PlatformSpaceServiceContractTest {

    @Test
    void shouldNormalizeBlankEnvironmentToProd() {
        PlatformSpaceService service = new PlatformSpaceService();

        assertEquals("prod", service.normalizeEnvironment(null));
        assertEquals("prod", service.normalizeEnvironment(" "));
        assertEquals("test", service.normalizeEnvironment("test"));
    }

    @Test
    void shouldReturnEmptyWhenSpaceCodeBlank() {
        PlatformSpaceService service = spy(new PlatformSpaceService());

        Optional<PlatformSpace> result = service.findActiveByCode(" ", "prod");

        assertTrue(result.isEmpty());
        verify(service, never()).getOne(any(Wrapper.class), eq(false));
    }

    @Test
    void shouldDelegateActiveLookupToGetOne() {
        PlatformSpaceService service = spy(new PlatformSpaceService());
        PlatformSpace row = new PlatformSpace();
        row.setSpaceCode("default");
        doReturn(row).when(service).getOne(any(Wrapper.class), eq(false));

        Optional<PlatformSpace> result = service.findActiveByCode("default", null);

        assertTrue(result.isPresent());
        assertEquals("default", result.get().getSpaceCode());
        verify(service).getOne(any(Wrapper.class), eq(false));
    }

}
