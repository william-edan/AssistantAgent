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
package com.alibaba.assistant.agent.runtime;

import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.tool.react.SlotConfirmTool;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.form.FormDisplayConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class SlotConfirmToolSpringContextTest {

    @Test
    void shouldInstantiateSlotConfirmToolBeanUnderMigrationProfile() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("migration");
        context.getBeanFactory().registerSingleton("slotSchemaParser", mock(SlotSchemaParser.class));
        context.getBeanFactory().registerSingleton("slotEnricherService", mock(SlotEnricherService.class));
        context.getBeanFactory().registerSingleton("computedFieldProcessor", mock(ComputedFieldProcessor.class));
        context.getBeanFactory().registerSingleton("formDisplayConfigService", mock(FormDisplayConfigService.class));
        context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
        context.getBeanFactory().registerSingleton(
                "artifactPublicationLookupService",
                mock(ArtifactPublicationLookupService.class));
        context.register(SlotConfirmTool.class);

        assertDoesNotThrow(context::refresh);
        assertNotNull(context.getBean(SlotConfirmTool.class));
        context.close();
    }
}
