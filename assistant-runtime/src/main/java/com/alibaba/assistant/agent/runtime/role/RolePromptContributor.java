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
package com.alibaba.assistant.agent.runtime.role;

import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Injects role persona and scenario context into the dynamic prompt chain.
 */
@Component
@Profile("migration")
public class RolePromptContributor implements PromptContributor {

    private final RoleContextResolver roleContextResolver;

    private final ScenarioRouter scenarioRouter;

    public RolePromptContributor(RoleContextResolver roleContextResolver, ScenarioRouter scenarioRouter) {
        this.roleContextResolver = roleContextResolver;
        this.scenarioRouter = scenarioRouter;
    }

    @Override
    public String getName() {
        return "role-prompt";
    }

    @Override
    public int getPriority() {
        return 110;
    }

    @Override
    public boolean shouldContribute(PromptContributorContext context) {
        return context != null && roleContextResolver.resolve(context.getAttributes()).isPresent();
    }

    @Override
    public PromptContribution contribute(PromptContributorContext context) {
        if (context == null) {
            return PromptContribution.empty();
        }
        Optional<RoleContextResolver.RoleContext> resolved = roleContextResolver.resolve(context.getAttributes());
        if (resolved.isEmpty()) {
            return PromptContribution.empty();
        }
        RoleContextResolver.RoleContext roleContext = resolved.get();
        String scenarioCode = StringUtils.hasText(roleContext.activeScenarioCode())
                ? roleContext.activeScenarioCode()
                : scenarioRouter.resolveScenario(context.getAttributes(), resolveLatestUserInput(context)).orElse(null);
        String text = buildPromptText(roleContext, scenarioCode);
        if (!StringUtils.hasText(text)) {
            return PromptContribution.empty();
        }
        return PromptContribution.builder()
                .append(new UserMessage(text))
                .build();
    }

    private String resolveLatestUserInput(PromptContributorContext context) {
        List<Message> messages = context.getMessages();
        if (messages != null && !messages.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if (message != null && StringUtils.hasText(message.getText())) {
                    return message.getText();
                }
            }
        }
        Map<String, Object> attributes = context.getAttributes();
        Object input = attributes.get("input");
        return input != null ? String.valueOf(input) : null;
    }

    private String buildPromptText(RoleContextResolver.RoleContext roleContext, String scenarioCode) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(roleContext.displayName()) || StringUtils.hasText(roleContext.persona())) {
            builder.append("【岗位角色】\n");
            if (StringUtils.hasText(roleContext.displayName())) {
                builder.append("当前岗位：").append(roleContext.displayName()).append(" (")
                        .append(roleContext.roleCode()).append(")\n");
            }
            if (StringUtils.hasText(roleContext.persona())) {
                builder.append("岗位职责：").append(roleContext.persona()).append("\n");
            }
        }
        if (StringUtils.hasText(scenarioCode)) {
            roleContext.findScenario(scenarioCode).ifPresent(scenario -> {
                builder.append("\n【当前场景】\n");
                builder.append("场景编码：").append(scenario.scenarioCode()).append("\n");
                if (StringUtils.hasText(scenario.displayName())) {
                    builder.append("场景名称：").append(scenario.displayName()).append("\n");
                }
                if (StringUtils.hasText(scenario.description())) {
                    builder.append("场景说明：").append(scenario.description()).append("\n");
                }
            });
        }
        return builder.toString().trim();
    }
}
