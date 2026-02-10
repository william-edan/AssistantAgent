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
package com.alibaba.assistant.agent.start.saas.app.service;

import com.alibaba.assistant.agent.start.saas.context.SaaSTenantContextHolder;
import com.alibaba.assistant.agent.start.saas.controller.dto.CapabilityRecallResponse;
import com.alibaba.assistant.agent.start.saas.domain.model.CapabilityStatus;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.CapabilityDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.CapabilityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Semantic capability recall service based on vector similarity.
 *
 * <p>This implementation provides a deterministic hash embedding as default
 * vector provider, and can be replaced with real embedding services later.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class CapabilitySemanticRecallService {

    private static final int EMBEDDING_DIM = 64;

    private final CapabilityMapper capabilityMapper;

    public CapabilitySemanticRecallService(CapabilityMapper capabilityMapper) {
        this.capabilityMapper = capabilityMapper;
    }

    /**
     * Recall topK published capabilities by vector similarity.
     *
     * @param tenantId tenant id
     * @param query user query
     * @param topK top k
     * @return candidates
     */
    public List<CapabilityRecallResponse> recall(String tenantId, String query, Integer topK) {
        return runWithTenant(tenantId, () -> {
            int effectiveTopK = (topK == null || topK <= 0) ? 5 : Math.min(topK, 20);
            if (query == null || query.isBlank()) {
                return List.of();
            }
            double[] queryVector = embed(query);
            List<CapabilityDO> published = capabilityMapper.selectList(Wrappers.lambdaQuery(CapabilityDO.class)
                    .eq(CapabilityDO::getStatus, CapabilityStatus.PUBLISHED.name()));
            List<CapabilityRecallResponse> recalls = new ArrayList<>();
            for (CapabilityDO capability : published) {
                String summary = buildSummary(capability);
                double score = cosine(queryVector, embed(summary));
                if (score <= 0D) {
                    continue;
                }
                CapabilityRecallResponse response = new CapabilityRecallResponse();
                response.setCapabilityId(capability.getCapabilityId());
                response.setDisplayName(capability.getDisplayName());
                response.setVersionNo(capability.getLatestVersion());
                response.setScore(score);
                recalls.add(response);
            }
            return recalls.stream()
                    .sorted(Comparator.comparing(CapabilityRecallResponse::getScore).reversed())
                    .limit(effectiveTopK)
                    .collect(Collectors.toList());
        });
    }

    private String buildSummary(CapabilityDO capability) {
        return String.join(" ",
                safe(capability.getCapabilityId()),
                safe(capability.getDisplayName()),
                safe(capability.getDomainCode()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double[] embed(String text) {
        double[] vector = new double[EMBEDDING_DIM];
        Map<String, Integer> tokenFreq = tokenize(text);
        for (Map.Entry<String, Integer> entry : tokenFreq.entrySet()) {
            int bucket = Math.abs(entry.getKey().hashCode()) % EMBEDDING_DIM;
            vector[bucket] += entry.getValue();
        }
        return normalize(vector);
    }

    private Map<String, Integer> tokenize(String text) {
        Map<String, Integer> freq = new HashMap<>();
        String normalized = text.toLowerCase();
        String[] tokens = normalized.split("[^\\p{L}\\p{N}_]+");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            freq.put(token, freq.getOrDefault(token, 0) + 1);
        }
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .mapToObj(code -> "ch:" + new String(Character.toChars(code)))
                .forEach(token -> freq.put(token, freq.getOrDefault(token, 0) + 1));
        return freq;
    }

    private double[] normalize(double[] vector) {
        double norm = 0D;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm <= 0D) {
            return vector;
        }
        double base = Math.sqrt(norm);
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / base;
        }
        return normalized;
    }

    private double cosine(double[] left, double[] right) {
        double sum = 0D;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }

    private <T> T runWithTenant(String tenantId, Supplier<T> supplier) {
        String old = SaaSTenantContextHolder.getTenantId();
        SaaSTenantContextHolder.setTenantId(tenantId);
        try {
            return supplier.get();
        }
        finally {
            if (old == null || old.isBlank()) {
                SaaSTenantContextHolder.clear();
            }
            else {
                SaaSTenantContextHolder.setTenantId(old);
            }
        }
    }
}
