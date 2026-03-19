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
package com.alibaba.assistant.agent.execution.persistence.mapper;

import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProactiveRunLeaseMapperContractTest {

    @Test
    void shouldReturnLatestScheduledAtWhenTaskHasHistoricalRows() {
        ProactiveRunLeaseMapper mapper = mock(ProactiveRunLeaseMapper.class, CALLS_REAL_METHODS);
        LocalDateTime latestScheduledAt = LocalDateTime.of(2026, 3, 19, 13, 30);
        ProactiveRunLease latest = new ProactiveRunLease();
        latest.setId(9L);
        latest.setTaskKey("finance|cleanup");
        latest.setScheduledAt(latestScheduledAt);

        when(mapper.selectOne(anyWrapper()))
                .thenThrow(new TooManyResultsException("Expected one result (or null) to be returned by selectOne(), but found: 2"));
        when(mapper.selectList(anyWrapper())).thenReturn(List.of(latest));

        Optional<LocalDateTime> result = mapper.findLatestScheduledAt("finance|cleanup");

        assertEquals(Optional.of(latestScheduledAt), result);
        verify(mapper).selectList(anyWrapper());
        verify(mapper, never()).selectOne(anyWrapper());
    }

    @SuppressWarnings("unchecked")
    private static Wrapper<ProactiveRunLease> anyWrapper() {
        return any(Wrapper.class);
    }
}
