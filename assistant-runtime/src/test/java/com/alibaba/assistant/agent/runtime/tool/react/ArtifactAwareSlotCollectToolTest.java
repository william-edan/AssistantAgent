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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.planner.DependencyResolver;
import com.alibaba.assistant.agent.runtime.planner.FieldMappingProcessor;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublicationScopeResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.SlotCollectorService;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotCollectStatus;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotPriority;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactAwareSlotCollectToolTest {

    @Test
    void shouldResolveSlotSchemaFromPublishedArtifactWhenOnlyToolCodeIsProvided() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        stubCompleteCollection(collector, parser);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor("oa.leave.apply", "gougu_oa")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                lookupService,
                null);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "oa.leave.apply";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, null);

        ArgumentCaptor<ToolMetaSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ToolMetaSnapshot.class);
        verify(parser).parse(snapshotCaptor.capture());
        assertEquals("oa.leave.apply", snapshotCaptor.getValue().getToolCode());
        assertEquals("gougu_oa", snapshotCaptor.getValue().getSystemCode());
        assertTrue(snapshotCaptor.getValue().getSlotSchema().contains("reason"));
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
    }


    @Test
    void shouldLookupPublishedArtifactWhenStateMetaMapLacksSlotSchema() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        stubCompleteCollection(collector, parser);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor("oa.leave.apply", "gougu_oa")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                lookupService,
                null);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.MATCHED_TOOL_META,
                Map.of(
                        "toolCode", "oa.leave.apply",
                        "systemCode", "gougu_oa")));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "oa.leave.apply";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, toolContext);

        ArgumentCaptor<ToolMetaSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ToolMetaSnapshot.class);
        verify(parser).parse(snapshotCaptor.capture());
        assertEquals("oa.leave.apply", snapshotCaptor.getValue().getToolCode());
        assertTrue(snapshotCaptor.getValue().getSlotSchema().contains("reason"));
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
    }
    @Test
    void shouldPreferPublishedArtifactBeforeLegacyToolMetaFallback() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        stubCompleteCollection(collector, parser);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor("oa.leave.apply", "gougu_oa")));
        when(toolMetaService.findLatestEnabledByToolCode("default", "oa.leave.apply"))
                .thenReturn(Optional.of(legacyToolMeta("oa.leave.apply")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                toolMetaService,
                null,
                null,
                null,
                lookupService,
                null);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "oa.leave.apply";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, null);

        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        verify(toolMetaService, never()).findLatestEnabledByToolCode(anyString(), eq("oa.leave.apply"));
    }

    @Test
    void shouldNotFallbackToLegacyToolMetaWhenScopedCallDefaultsToArtifactOnly() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        when(lookupService.findPublishedArtifact(eq("gougu_oa.leave_application"), any()))
                .thenReturn(Optional.empty());
        when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.leave_application"))
                .thenReturn(Optional.of(legacyToolMeta("gougu_oa.leave_application")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                toolMetaService,
                null,
                null,
                null,
                lookupService,
                publicationScopeResolver);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "gougu_oa.leave_application";

        SlotCollectTool.Response response = tool.apply(request, scopedToolContext(false));

        assertEquals("ERROR", response.status);
        assertEquals("Missing tool meta snapshot or slot schema", response.message);
        verify(parser, never()).parse(any(ToolMetaSnapshot.class));
        verify(toolMetaService, never()).findLatestEnabledByToolCode(anyString(), eq("gougu_oa.leave_application"));
    }

    @Test
    void shouldFallbackToLegacyToolMetaWhenScopedCallAllowsLegacyFallback() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SlotCollectTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        stubCompleteCollection(collector, parser);
        when(lookupService.findPublishedArtifact(eq("gougu_oa.leave_application"), any()))
                .thenReturn(Optional.empty());
        when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.leave_application"))
                .thenReturn(Optional.of(legacyToolMeta("gougu_oa.leave_application")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                toolMetaService,
                null,
                null,
                null,
                lookupService,
                publicationScopeResolver);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "gougu_oa.leave_application";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, scopedToolContext(true));

        ArgumentCaptor<ToolMetaSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ToolMetaSnapshot.class);
        verify(parser).parse(snapshotCaptor.capture());
        assertEquals("gougu_oa.leave_application", snapshotCaptor.getValue().getToolCode());
        assertTrue(snapshotCaptor.getValue().getSlotSchema().contains("reason"));
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        verify(toolMetaService, times(1)).findLatestEnabledByToolCode("default", "gougu_oa.leave_application");
        }

    @Test
    void shouldLogWarningWhenScopedCallFallsBackToLegacyToolMeta() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SlotCollectTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            stubCompleteCollection(collector, parser);
            when(lookupService.findPublishedArtifact(eq("gougu_oa.leave_application"), any()))
                    .thenReturn(Optional.empty());
            when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.leave_application"))
                    .thenReturn(Optional.of(legacyToolMeta("gougu_oa.leave_application")));

            SlotCollectTool tool = new SlotCollectTool(
                    collector,
                    enricher,
                    computed,
                    parser,
                    new ObjectMapper(),
                    toolMetaService,
                    null,
                    null,
                    null,
                    lookupService,
                    publicationScopeResolver);
            SlotCollectTool.Request request = new SlotCollectTool.Request();
            request.toolCode = "gougu_oa.leave_application";
            request.extractedSlots = Map.of("reason", "事假");

            SlotCollectTool.Response response = tool.apply(request, scopedToolContext(true));

            assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logs.contains("SlotCollectTool#resolveToolMetaSnapshot - compatibility fallback to legacy ToolMeta"));
            assertTrue(logs.contains("mode=fallback"));
            assertTrue(logs.contains("toolCode=gougu_oa.leave_application"));
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldScopeLegacyFallbackQueryToTenantWhenDirectLookupFallsBack() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        stubCompleteCollection(collector, parser);
        when(lookupService.findPublishedArtifact(eq("gougu_oa.leave_application"), any()))
                .thenReturn(Optional.empty());
        when(toolMetaService.findLatestEnabledByToolCode("tenant-a", "gougu_oa.leave_application"))
                .thenReturn(Optional.empty());
        when(toolMetaService.getOne(any(LambdaQueryWrapper.class), eq(false)))
                .thenReturn(legacyToolMeta("gougu_oa.leave_application"));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                toolMetaService,
                null,
                null,
                null,
                lookupService,
                publicationScopeResolver);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "gougu_oa.leave_application";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, scopedToolContext("tenant-a", true));

        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(toolMetaService).getOne(queryCaptor.capture(), eq(false));
        TableInfoHelper.remove(ToolMeta.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), ToolMeta.class);
        String sqlSegment = queryCaptor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("tenantId =")
                        && sqlSegment.contains("tenantId IS NULL")
                        && sqlSegment.contains("ORDER BY version DESC,id DESC"),
                () -> "sqlSegment=" + sqlSegment);
    }


    @Test
    void shouldResolveDependencyStepsFromPublishedArtifactsWhenToolMetaIsUnavailable() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        DependencyResolver dependencyResolver = new DependencyResolver(new ObjectMapper());

        SlotDefinition employeeId = new SlotDefinition();
        employeeId.setName("employeeId");
        employeeId.setType("string");
        employeeId.setPriority(SlotPriority.CORE);
        employeeId.setAskMode(SlotAskMode.AUTO);
        employeeId.setRequired(true);
        SlotDefinition reason = new SlotDefinition();
        reason.setName("reason");
        reason.setType("string");
        reason.setPriority(SlotPriority.CORE);
        reason.setAskMode(SlotAskMode.BATCH);
        reason.setRequired(true);
        List<SlotDefinition> definitions = List.of(employeeId, reason);
        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.collectFromAgent(anyMap(), anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> existing = invocation.getArgument(2);
            Map<String, SlotValue> collected = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
            extraction.forEach((key, value) -> collected.put(key,
                    SlotValue.resolved(key, String.valueOf(value), value, String.valueOf(value))));
            return collected;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            Map<String, Object> finalParams = new LinkedHashMap<>();
            slotValues.forEach((key, value) -> finalParams.put(key, value.getResolvedValue()));
            return finalParams;
        });
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor(
                        "oa.leave.apply",
                        "gougu_oa",
                        "{\"dependsOn\":[\"oa.current.user\"],\"fieldMappings\":[{\"fromTool\":\"oa.current.user\",\"fromField\":\"employeeId\",\"toField\":\"employeeId\"}]}")));
        when(lookupService.findPublishedArtifact(eq("oa.current.user"), any()))
                .thenReturn(Optional.of(descriptor("oa.current.user", "gougu_oa", null)));
        when(toolMetaService.findLatestEnabledByToolCode(anyString(), anyString())).thenReturn(Optional.empty());
        when(toolExecutor.execute(eq("default"), eq("oa.current.user"), anyMap(), any()))
                .thenReturn(ToolExecutor.ExecutionResult.success(
                        "oa.current.user",
                        Map.of("success", true),
                        Map.of("employeeId", "E001")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                toolMetaService,
                dependencyResolver,
                new FieldMappingProcessor(),
                toolExecutor,
                lookupService,
                publicationScopeResolver);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "oa.leave.apply";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, scopedToolContext(false));

        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        assertEquals("E001", response.collected.get("employeeId"));
        verify(toolExecutor, times(1)).execute(eq("default"), eq("oa.current.user"), anyMap(), any());
    }

    @Test
    void shouldLogWarningWhenDependencyResolutionFallsBackToLegacyToolMeta() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        DependencyResolver dependencyResolver = new DependencyResolver(new ObjectMapper());
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SlotCollectTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            SlotDefinition employeeId = new SlotDefinition();
            employeeId.setName("employeeId");
            employeeId.setType("string");
            employeeId.setPriority(SlotPriority.CORE);
            employeeId.setAskMode(SlotAskMode.AUTO);
            employeeId.setRequired(true);
            SlotDefinition reason = new SlotDefinition();
            reason.setName("reason");
            reason.setType("string");
            reason.setPriority(SlotPriority.CORE);
            reason.setAskMode(SlotAskMode.BATCH);
            reason.setRequired(true);
            when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(List.of(employeeId, reason));
            when(collector.collectFromAgent(anyMap(), anyList(), anyMap())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> extraction = invocation.getArgument(0);
                @SuppressWarnings("unchecked")
                Map<String, SlotValue> existing = invocation.getArgument(2);
                Map<String, SlotValue> collected = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
                extraction.forEach((key, value) -> collected.put(key,
                        SlotValue.resolved(key, String.valueOf(value), value, String.valueOf(value))));
                return collected;
            });
            when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
            when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, SlotValue> slotValues = invocation.getArgument(1);
                Map<String, Object> finalParams = new LinkedHashMap<>();
                slotValues.forEach((key, value) -> finalParams.put(key, value.getResolvedValue()));
                return finalParams;
            });
            when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                    .thenReturn(Optional.of(descriptor(
                            "oa.leave.apply",
                            "gougu_oa",
                            "{\"dependsOn\":[\"oa.current.user\"],\"fieldMappings\":[{\"fromTool\":\"oa.current.user\",\"fromField\":\"employeeId\",\"toField\":\"employeeId\"}]}")));
            when(lookupService.findPublishedArtifact(eq("oa.current.user"), any()))
                    .thenReturn(Optional.empty());
            when(toolMetaService.findLatestEnabledByToolCode("default", "oa.current.user"))
                    .thenReturn(Optional.of(legacyToolMeta("oa.current.user")));
            when(toolExecutor.execute(eq("default"), eq("oa.current.user"), anyMap(), any()))
                    .thenReturn(ToolExecutor.ExecutionResult.success(
                            "oa.current.user",
                            Map.of("success", true),
                            Map.of("employeeId", "E001")));

            SlotCollectTool tool = new SlotCollectTool(
                    collector,
                    enricher,
                    computed,
                    parser,
                    new ObjectMapper(),
                    toolMetaService,
                    dependencyResolver,
                    new FieldMappingProcessor(),
                    toolExecutor,
                    lookupService,
                    publicationScopeResolver);
            SlotCollectTool.Request request = new SlotCollectTool.Request();
            request.toolCode = "oa.leave.apply";
            request.extractedSlots = Map.of("reason", "事假");

            SlotCollectTool.Response response = tool.apply(request, scopedToolContext(true));

            assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logs.contains("SlotCollectTool#resolveDependencySteps - compatibility fallback to legacy dependency ToolMeta"));
            assertTrue(logs.contains("mode=fallback"));
            assertTrue(logs.contains("toolCode=oa.current.user"));
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void stubCompleteCollection(SlotCollectorService collector, SlotSchemaParser parser) {
        SlotDefinition reason = new SlotDefinition();
        reason.setName("reason");
        reason.setType("string");
        reason.setPriority(SlotPriority.CORE);
        reason.setAskMode(SlotAskMode.BATCH);
        reason.setRequired(true);
        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(List.of(reason));
        when(collector.collectFromAgent(anyMap(), anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> existing = invocation.getArgument(2);
            Map<String, SlotValue> collected = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
            extraction.forEach((key, value) -> collected.put(key,
                    SlotValue.resolved(key, String.valueOf(value), value, String.valueOf(value))));
            return collected;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            Map<String, Object> finalParams = new LinkedHashMap<>();
            slotValues.forEach((key, value) -> finalParams.put(key, value.getResolvedValue()));
            return finalParams;
        });
    }

    private ToolMeta legacyToolMeta(String toolCode) {
        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode(toolCode);
        toolMeta.setToolName("请假申请");
        toolMeta.setDescription("发起请假申请审批");
        toolMeta.setSystemCode("gougu_oa");
        toolMeta.setParameterSchema("{\"slots\":[{\"name\":\"reason\",\"type\":\"string\",\"required\":true}]}");
        return toolMeta;
    }

    private ToolContext scopedToolContext(boolean allowLegacyFallback) {
        return scopedToolContext("default", allowLegacyFallback);
    }

    private ToolContext scopedToolContext(String tenantId, boolean allowLegacyFallback) {
        OverAllState state = new OverAllState();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenant_id", tenantId);
        values.put(AssistantStateKeys.SPACE_ID, 9L);
        values.put(AssistantStateKeys.SPACE_ENVIRONMENT, "prod");
        values.put(AssistantStateKeys.AGENT_APP_CODE, "finance-agent");
        if (allowLegacyFallback) {
            values.put("allow_legacy_fallback", true);
        }
        state.updateState(values);
        return new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
    }

    private PublishedToolDescriptor descriptor(String artifactCode, String systemCode) {
        return descriptor(artifactCode, systemCode, null);
    }

    private PublishedToolDescriptor descriptor(String artifactCode, String systemCode, String askStrategyJson) {
        RuntimeArtifact.Interaction interaction = new RuntimeArtifact.Interaction(
                1L,
                artifactCode + ".interaction",
                "{\"slots\":[{\"name\":\"reason\",\"type\":\"string\",\"required\":true}]}",
                askStrategyJson,
                null,
                null,
                null,
                null);
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                interaction,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:" + artifactCode,
                "请假申请",
                null,
                null,
                false,
                systemCode,
                artifact);
    }
}

