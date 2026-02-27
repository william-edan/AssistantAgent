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
package com.alibaba.assistant.agent.autoconfigure;

import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static Agent Loader for Codeact agents
 *
 * <p>这个加载器将预创建的 CodeactAgent 实例通过 AgentLoader 接口暴露给 spring-ai-alibaba-studio。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class AgentStaticLoader implements AgentLoader {

	private static final Logger logger = LoggerFactory.getLogger(AgentStaticLoader.class);

	private final Map<String, BaseAgent> agents = new ConcurrentHashMap<>();

	/**
	 * 构造函数，接收 ReactAgent 并注册到加载器中
	 *
	 * @param agent ReactAgent 实例
	 */
	public AgentStaticLoader(ReactAgent agent) {
		logger.info("AgentStaticLoader#<init> 初始化 Agent 加载器");

		// 编译并打印 agent 的图结构
		GraphRepresentation representation = agent.getAndCompileGraph()
				.stateGraph
				.getGraph(GraphRepresentation.Type.PLANTUML);

		logger.debug("AgentStaticLoader#<init> Agent 图结构:\n{}", representation.content());
		System.out.println("\n=== Agent Graph Structure ===");
		System.out.println(representation.content());
		System.out.println("=============================\n");

		// 注册 agent，使用 "grayscale_agent" 作为标识符
		this.agents.put("grayscale_agent", agent);
		// 兼容 migration 侧默认 appName
		this.agents.put("assistant_agent", agent);

		logger.info("AgentStaticLoader#<init> 已注册 agent: {}", this.agents.keySet());
	}

	@Override
	@Nonnull
	public List<String> listAgents() {
		logger.debug("AgentStaticLoader#listAgents 列出所有 agent");
		return agents.keySet().stream().toList();
	}

	@Override
	public BaseAgent loadAgent(String name) {
		logger.info("AgentStaticLoader#loadAgent 加载 agent: {}", name);

		if (name == null || name.trim().isEmpty()) {
			logger.error("AgentStaticLoader#loadAgent Agent 名称为空");
			throw new IllegalArgumentException("Agent name cannot be null or empty");
		}
		BaseAgent agent = agents.get(name);
		if (agent != null) {
			logger.info("AgentStaticLoader#loadAgent 成功加载 agent: {}", name);
			return agent;
		}
		logger.warn("AgentStaticLoader#loadAgent 未找到指定 agent, requested={}, fallback=grayscale_agent", name);
		return agents.get("grayscale_agent");
	}
}

