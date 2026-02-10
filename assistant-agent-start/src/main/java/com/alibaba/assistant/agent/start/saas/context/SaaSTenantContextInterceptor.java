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
package com.alibaba.assistant.agent.start.saas.context;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interceptor to bind tenant id from URL into thread local context.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SaaSTenantContextInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SaaSTenantContextInterceptor.class);

    private static final Pattern TENANT_PATTERN = Pattern.compile("/api/v1/tenant/([^/]+)/");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        Matcher matcher = TENANT_PATTERN.matcher(uri + "/");
        if (matcher.find()) {
            String tenantId = matcher.group(1);
            SaaSTenantContextHolder.setTenantId(tenantId);
            log.debug("SaaSTenantContextInterceptor#preHandle - reason=tenant context bound, tenantId={}", tenantId);
        }
        else {
            SaaSTenantContextHolder.setTenantId("default");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        SaaSTenantContextHolder.clear();
    }
}
