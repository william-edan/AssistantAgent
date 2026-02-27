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
package com.alibaba.assistant.agent.runtime.adapter;

import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.slot.port.SystemAccessProfilePort;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Property-driven system access profile adapter used by slot/execution modules.
 *
 * <p>Property lookup order:
 * <ol>
 *   <li>{@code assistant.system-profiles.<systemCode>.<field>}</li>
 *   <li>{@code assistant.system-default.<field>}</li>
 * </ol>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class PropertySystemAccessProfileAdapter
        implements SystemAccessProfilePort, HttpStepExecutor.SystemAccessProfilePort {

    private final Environment environment;

    public PropertySystemAccessProfileAdapter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String getBaseUrl(String systemCode) {
        return resolveProperty(systemCode, "base-url");
    }

    @Override
    public String getTokenHeaderName(String systemCode) {
        String configured = resolveProperty(systemCode, "token-header-name");
        return StringUtils.hasText(configured) ? configured : "Authorization";
    }

    @Override
    public String getTokenHeaderPrefix(String systemCode) {
        String configured = resolveProperty(systemCode, "token-header-prefix");
        return StringUtils.hasText(configured) ? configured : "Bearer ";
    }

    private String resolveProperty(String systemCode, String field) {
        if (StringUtils.hasText(systemCode)) {
            String specific = environment.getProperty("assistant.system-profiles." + systemCode + "." + field);
            if (StringUtils.hasText(specific)) {
                return specific;
            }
        }
        return environment.getProperty("assistant.system-default." + field);
    }
}
