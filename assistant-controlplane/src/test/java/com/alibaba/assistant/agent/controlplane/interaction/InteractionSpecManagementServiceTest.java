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
package com.alibaba.assistant.agent.controlplane.interaction;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InteractionSpecManagementServiceTest {

    @Test
    void shouldListEnabledInteractionsUnderSpace() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        InteractionSpecManagementService service = new InteractionSpecManagementService(
                platformSpaceService,
                interactionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        InteractionSpec interaction = interactionSpec(31L, 10L, "leave.apply.form", "enabled");
        interaction.setSlotSchemaJson("{\"slots\":[{\"name\":\"reason\"}]}");
        interaction.setAskStrategyJson("{\"mode\":\"batch\"}");
        interaction.setAutoFillRulesJson("{\"duration\":\"date_diff\"}");
        interaction.setSummaryLayoutJson("{\"sections\":[\"core\"]}");
        interaction.setConfirmationPolicyJson("{\"required\":true}");
        interaction.setEditPolicyJson("{\"allowEdit\":true}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(interactionSpecService.listEnabledBySpace(10L)).thenReturn(List.of(interaction));

        List<ResolvedInteractionSpecManagementView> result = service.listInteractions("enterprise-default", "prod");

        assertEquals(1, result.size());
        assertEquals("leave.apply.form", result.get(0).interactionCode());
        assertEquals("batch", result.get(0).askStrategy().get("mode"));
        assertEquals(Boolean.TRUE, result.get(0).confirmationPolicy().get("required"));
    }

    @Test
    void shouldCreateInteractionWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        InteractionSpecManagementService service = new InteractionSpecManagementService(
                platformSpaceService,
                interactionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(interactionSpecService.findLatestEnabledByCode(11L, "leave.apply.form")).thenReturn(Optional.empty());
        when(interactionSpecService.save(any(InteractionSpec.class))).thenReturn(true);

        Optional<ResolvedInteractionSpecManagementView> result = service.upsertInteraction(
                "enterprise-default",
                "prod",
                "leave.apply.form",
                new InteractionSpecUpsertCommand(
                        Map.of("slots", List.of(Map.of("name", "reason"))),
                        Map.of("mode", "batch"),
                        Map.of("duration", "date_diff"),
                        Map.of("sections", List.of("core")),
                        Map.of("required", true),
                        Map.of("allowEdit", true),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("leave.apply.form", result.get().interactionCode());
        assertEquals("batch", result.get().askStrategy().get("mode"));
    }

    @Test
    void shouldUpdateInteractionWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        InteractionSpecManagementService service = new InteractionSpecManagementService(
                platformSpaceService,
                interactionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        InteractionSpec existing = interactionSpec(41L, 12L, "leave.apply.form", "enabled");
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(interactionSpecService.findLatestEnabledByCode(12L, "leave.apply.form")).thenReturn(Optional.of(existing));
        when(interactionSpecService.updateById(any(InteractionSpec.class))).thenReturn(true);

        Optional<ResolvedInteractionSpecManagementView> result = service.upsertInteraction(
                "enterprise-default",
                "test",
                "leave.apply.form",
                new InteractionSpecUpsertCommand(
                        Map.of("slots", List.of(Map.of("name", "types"))),
                        Map.of("mode", "progressive"),
                        Map.of("duration", "recompute"),
                        Map.of("sections", List.of("core", "secondary")),
                        Map.of("required", false),
                        Map.of("allowEdit", false),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("progressive", result.get().askStrategy().get("mode"));
        assertEquals(Boolean.FALSE, result.get().confirmationPolicy().get("required"));
        assertEquals(Boolean.FALSE, result.get().editPolicy().get("allowEdit"));
    }

    private InteractionSpec interactionSpec(Long id, Long spaceId, String interactionCode, String status) {
        InteractionSpec interaction = new InteractionSpec();
        interaction.setId(id);
        interaction.setSpaceId(spaceId);
        interaction.setInteractionCode(interactionCode);
        interaction.setStatus(status);
        return interaction;
    }
}
