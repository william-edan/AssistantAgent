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
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.runtime.task.AgentTaskProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class ArtifactRuntimeExecutorSpringContextTest {

    @Test
    void shouldInjectExecutionEventRegistryAndTaskProjectorUnderMigrationProfile() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("migration");
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        CredentialBroker credentialBroker = mock(CredentialBroker.class);
        ExecutionRuntimePersistenceRecorder persistenceRecorder = mock(ExecutionRuntimePersistenceRecorder.class);
        ExecutionEventStreamRegistry executionEventStreamRegistry = new ExecutionEventStreamRegistry();
        AgentTaskProjector agentTaskProjector = mock(AgentTaskProjector.class);
        ObjectMapper objectMapper = new ObjectMapper();
        context.getBeanFactory().registerSingleton("dagFlowExecutor", dagFlowExecutor);
        context.getBeanFactory().registerSingleton("credentialBroker", credentialBroker);
        context.getBeanFactory().registerSingleton("executionRuntimePersistenceRecorder", persistenceRecorder);
        context.getBeanFactory().registerSingleton("executionEventStreamRegistry", executionEventStreamRegistry);
        context.getBeanFactory().registerSingleton("agentTaskProjector", agentTaskProjector);
        context.getBeanFactory().registerSingleton("objectMapper", objectMapper);
        context.register(ArtifactRuntimeExecutor.class);

        assertDoesNotThrow(context::refresh);
        ArtifactRuntimeExecutor executor = context.getBean(ArtifactRuntimeExecutor.class);
        assertNotNull(executor);
        assertSame(
                executionEventStreamRegistry,
                ReflectionTestUtils.getField(executor, "executionEventStreamRegistry"));
        assertSame(agentTaskProjector, ReflectionTestUtils.getField(executor, "agentTaskProjector"));
        context.close();
    }
}
