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
package com.alibaba.assistant.agent.start.reward.config;

import com.alibaba.assistant.agent.start.reward.client.RewardsClient;
import com.alibaba.assistant.agent.start.reward.node.RewardAutoExecuteNode;
import com.alibaba.assistant.agent.start.reward.node.RewardFormNode;
import com.alibaba.assistant.agent.start.reward.node.RewardIntentNode;
import com.alibaba.assistant.agent.start.reward.service.DataAgentService;
import com.alibaba.assistant.agent.start.reward.service.RewardEmployeeHttpService;
import com.alibaba.assistant.agent.start.reward.util.DataAgentResultParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reward workflow configuration.
 */
@Configuration
@Profile("migration")
public class RewardAgentConfiguration {

    @Bean("rewardDataAgentWebClient")
    public WebClient rewardDataAgentWebClient(
            @Value("${assistant.reward.data-agent.base-url:http://localhost:8066}") String baseUrl,
            @Value("${assistant.reward.data-agent.connect-timeout-ms:3000}") int connectTimeoutMillis) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public DataAgentResultParser dataAgentResultParser(ObjectMapper objectMapper) {
        return new DataAgentResultParser(objectMapper);
    }

    @Bean
    public RewardIntentNode rewardIntentNode() {
        return new RewardIntentNode();
    }

    @Bean
    public RewardFormNode rewardFormNode(
            RewardEmployeeHttpService rewardEmployeeHttpService,
            RewardsClient rewardsClient) {
        return new RewardFormNode(rewardEmployeeHttpService, rewardsClient);
    }

    @Bean
    public RewardAutoExecuteNode rewardAutoExecuteNode(
            DataAgentService dataAgentService,
            RewardsClient rewardsClient,
            DataAgentResultParser resultParser,
            @Value("${assistant.reward.batch.concurrency:4}") int batchConcurrency) {
        return new RewardAutoExecuteNode(dataAgentService, rewardsClient, resultParser, batchConcurrency);
    }
}
