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
package com.alibaba.assistant.agent.controlplane.agentapp;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class AgentAppGrantServiceContractTest {

    @Test
    void shouldReturnEmptyWhenAgentAppIdNull() {
        AgentAppGrantService service = spy(new AgentAppGrantService(new com.fasterxml.jackson.databind.ObjectMapper()));

        List<AgentAppGrant> result = service.listByAgentAppId(null);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldDelegateGrantLookupToList() {
        AgentAppGrantService service = spy(new AgentAppGrantService(new com.fasterxml.jackson.databind.ObjectMapper()));
        AgentAppGrant row = new AgentAppGrant();
        row.setTargetCode("oa.leave.apply");
        doReturn(List.of(row)).when(service).list(any(Wrapper.class));

        List<AgentAppGrant> result = service.listByAgentAppId(1L);

        assertEquals(1, result.size());
        assertEquals("oa.leave.apply", result.get(0).getTargetCode());
        verify(service).list(any(Wrapper.class));
    }

    @Test
    void shouldAssemblePublicationSourcePolicyFromTypedGrantRows() {
        AgentAppGrantService service = spy(new AgentAppGrantService(new com.fasterxml.jackson.databind.ObjectMapper()));
        doReturn(List.of(
                grant("publication_source", "tool-meta-catalog", "allow", null),
                grant("publication_source", "mcp-gateway", "allow", null),
                grant("publication_source", "legacy-bridge", "deny", null),
                grant("publication_source_policy", "default", "allow", "{\"sourceSelectionMode\":\"exclusive\"}")))
                .when(service).list(any(Wrapper.class));

        AgentAppPublicationSourcePolicy policy = service.findPublicationSourcePolicy(7L).orElseThrow();

        assertEquals("EXCLUSIVE", policy.sourceSelectionMode());
        assertEquals(List.of("tool-meta-catalog", "mcp-gateway"), policy.allowedSourceIds());
        assertEquals(List.of("legacy-bridge"), policy.blockedSourceIds());
    }

    @Test
    void shouldReplacePublicationSourcePolicyUsingTypedGrants() {
        AgentAppGrantService service = spy(new AgentAppGrantService(new com.fasterxml.jackson.databind.ObjectMapper()));
        doReturn(true).when(service).remove(any(Wrapper.class));
        doReturn(true).when(service).saveBatch(any(Collection.class));

        boolean updated = service.replacePublicationSourcePolicy(
                7L,
                new AgentAppPublicationSourcePolicy("exclusive", List.of("tool-meta-catalog", "mcp-gateway"),
                        List.of("legacy-bridge")));

        assertTrue(updated);
        verify(service).remove(any(Wrapper.class));
        ArgumentCaptor<Collection<AgentAppGrant>> savedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(service).saveBatch(savedCaptor.capture());
        List<AgentAppGrant> saved = List.copyOf(savedCaptor.getValue());
        assertEquals(4, saved.size());
        assertEquals(List.of("publication_source", "publication_source", "publication_source", "publication_source_policy"),
                saved.stream().map(AgentAppGrant::getTargetType).toList());
        assertEquals(List.of("tool-meta-catalog", "mcp-gateway", "legacy-bridge", "default"),
                saved.stream().map(AgentAppGrant::getTargetCode).toList());
        assertEquals(List.of("allow", "allow", "deny", "allow"),
                saved.stream().map(AgentAppGrant::getGrantMode).toList());
        assertEquals("{\"sourceSelectionMode\":\"EXCLUSIVE\"}", saved.get(3).getConstraintsJson());
    }

    @Test
    void shouldClearPublicationSourcePolicyWithoutSavingRowsWhenPolicyEmpty() {
        AgentAppGrantService service = spy(new AgentAppGrantService(new com.fasterxml.jackson.databind.ObjectMapper()));
        doReturn(true).when(service).remove(any(Wrapper.class));

        boolean updated = service.replacePublicationSourcePolicy(
                7L,
                new AgentAppPublicationSourcePolicy("merge", List.of(), List.of()));

        assertTrue(updated);
        verify(service).remove(any(Wrapper.class));
        assertFalse(service.findPublicationSourcePolicy(null).isPresent());
    }

    private AgentAppGrant grant(String targetType, String targetCode, String grantMode, String constraintsJson) {
        AgentAppGrant grant = new AgentAppGrant();
        grant.setTargetType(targetType);
        grant.setTargetCode(targetCode);
        grant.setGrantMode(grantMode);
        grant.setConstraintsJson(constraintsJson);
        return grant;
    }
}

