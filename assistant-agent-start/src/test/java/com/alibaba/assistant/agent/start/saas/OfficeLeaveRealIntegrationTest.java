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
package com.alibaba.assistant.agent.start.saas;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Real integration test for office leave two-step flow.
 *
 * <p>Enable by system property:
 * {@code -Doffice.it.enabled=true}
 *
 * <p>Required properties:
 * {@code -Doffice.it.sessionToken=...}
 * {@code -Doffice.it.checkUids=...}
 * {@code -Doffice.it.checkUnames=...}
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.dashscope.api-key=dummy",
        "logging.level.com.alibaba.assistant.agent=INFO"
})
@EnabledIfSystemProperty(named = "office.it.enabled", matches = "true")
public class OfficeLeaveRealIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Should execute office leave two-step flow via real HTTP endpoints.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldExecuteOfficeLeaveTwoStepFlow() {
        String sessionToken = required("office.it.sessionToken");
        String checkUids = required("office.it.checkUids");
        String checkUnames = required("office.it.checkUnames");
        String baseUrl = property("office.it.baseUrl", "http://office.test");

        String tenant = "t_office_real";
        String capabilityId = "office_leave_two_step_real";

        Number connectorId = createConnector(tenant, "office_real", baseUrl);
        upsertConnectorAuth(tenant, connectorId.longValue(), sessionToken);
        registerApi(tenant, connectorId.longValue(), "office_leave_add_real", "/home/leaves/add");
        registerApi(tenant, connectorId.longValue(), "office_leave_submit_real", "/api/check/submit_check");
        createCapability(tenant, capabilityId);

        String routeConfigJson = buildRouteConfig(baseUrl);
        Map<String, Object> versionRequest = new HashMap<>();
        versionRequest.put("connectorId", connectorId.longValue());
        versionRequest.put("inputSchemaJson", "{\"type\":\"object\",\"required\":[\"start_date\",\"end_date\",\"duration\",\"types\",\"reason\",\"flow_id\",\"check_uames\",\"check_uids\"]}");
        versionRequest.put("slotSchemaJson", "{\"required\":[\"start_date\",\"end_date\",\"duration\",\"types\",\"reason\",\"flow_id\",\"check_uames\",\"check_uids\"]}");
        versionRequest.put("outputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("routeConfigJson", routeConfigJson);
        versionRequest.put("executionMode", "MUTATION");
        versionRequest.put("operator", "tester");
        Map versionBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/versions", versionRequest);
        Assertions.assertEquals(0, versionBody.get("code"));

        publishCapability(tenant, capabilityId, 1);

        Map<String, Object> chatRequest = new HashMap<>();
        chatRequest.put("requestId", "req-office-real-1");
        chatRequest.put("executorUserId", "u-office-real");
        chatRequest.put("capabilityId", capabilityId);
        chatRequest.put("userInput", "提交请假");
        chatRequest.put("input", Map.of(
                "start_date", property("office.it.startDate", "2026-02-10 00:00"),
                "end_date", property("office.it.endDate", "2026-02-10 00:04"),
                "duration", property("office.it.duration", "1"),
                "types", property("office.it.types", "1"),
                "reason", property("office.it.reason", "integration test"),
                "flow_id", property("office.it.flowId", "1"),
                "check_uames", checkUnames,
                "check_uids", checkUids,
                "check_copy_unames", property("office.it.copyUnames", ""),
                "check_copy_uids", property("office.it.copyUids", "")
        ));
        Map executeBody = post("/api/v1/tenant/" + tenant + "/conversations/s_office_real/chat", chatRequest);
        Assertions.assertEquals(0, executeBody.get("code"));
        Map data = (Map) executeBody.get("data");
        Assertions.assertEquals("DONE", data.get("status"));
    }

    private String buildRouteConfig(String baseUrl) {
        return """
                {
                  "nodes": [
                    {
                      "nodeCode": "leave_add",
                      "apiCode": "office_leave_add_real",
                      "requestMode": "FORM_URLENCODED",
                      "headers": {
                        "X-Requested-With": "XMLHttpRequest",
                        "Origin": "%s",
                        "Referer": "%s/home/leaves/add"
                      },
                      "requestBody": {
                        "start_date": "${input.start_date}",
                        "end_date": "${input.end_date}",
                        "duration": "${input.duration}",
                        "types": "${input.types}",
                        "reason": "${input.reason}",
                        "file": "",
                        "file_ids": "",
                        "flow_id": "${input.flow_id}",
                        "check_uames": "${input.check_uames}",
                        "check_uids": "${input.check_uids}",
                        "check_copy_unames": "${input.check_copy_unames}",
                        "check_copy_uids": "${input.check_copy_uids}",
                        "id": 0
                      }
                    },
                    {
                      "nodeCode": "leave_submit",
                      "apiCode": "office_leave_submit_real",
                      "requestMode": "FORM_URLENCODED",
                      "headers": {
                        "X-Requested-With": "XMLHttpRequest",
                        "Origin": "%s",
                        "Referer": "%s/home/leaves/add"
                      },
                      "requestBody": {
                        "start_date": "${input.start_date}",
                        "end_date": "${input.end_date}",
                        "duration": "${input.duration}",
                        "types": "${input.types}",
                        "reason": "${input.reason}",
                        "file": "",
                        "file_ids": "",
                        "flow_id": "${input.flow_id}",
                        "check_uames": "${input.check_uames}",
                        "check_uids": "${input.check_uids}",
                        "check_copy_unames": "${input.check_copy_unames}",
                        "check_copy_uids": "${input.check_copy_uids}",
                        "id": 0,
                        "check_name": "leaves",
                        "action_id": "${node.leave_add.data.action_id}"
                      }
                    }
                  ],
                  "edges": [
                    { "from": "leave_add", "to": "leave_submit" }
                  ]
                }
                """.formatted(baseUrl, baseUrl, baseUrl, baseUrl);
    }

    private Number createConnector(String tenant, String connectorCode, String baseUrl) {
        Map<String, Object> connectorCreate = new HashMap<>();
        connectorCreate.put("connectorCode", connectorCode);
        connectorCreate.put("displayName", "Office OA Real");
        connectorCreate.put("connectorType", "HTTP");
        connectorCreate.put("baseUrl", baseUrl);
        connectorCreate.put("operator", "tester");
        Map connectorCreateBody = post("/api/v1/tenant/" + tenant + "/connectors", connectorCreate);
        Assertions.assertEquals(0, connectorCreateBody.get("code"));
        return (Number) ((Map<?, ?>) connectorCreateBody.get("data")).get("id");
    }

    private void upsertConnectorAuth(String tenant, Long connectorId, String sessionToken) {
        Map<String, Object> connectorAuth = new HashMap<>();
        connectorAuth.put("authType", "SESSION");
        connectorAuth.put("authConfigJson", "{\"sessionCookieName\":\"PHPSESSID\",\"sessionToken\":\"" + sessionToken + "\"}");
        connectorAuth.put("operator", "tester");
        Map connectorAuthBody = put("/api/v1/tenant/" + tenant + "/connectors/" + connectorId + "/auth", connectorAuth);
        Assertions.assertEquals(0, connectorAuthBody.get("code"));
    }

    private void registerApi(String tenant, Long connectorId, String apiCode, String pathTemplate) {
        Map<String, Object> apiRegister = new HashMap<>();
        apiRegister.put("apiCode", apiCode);
        apiRegister.put("displayName", apiCode);
        apiRegister.put("httpMethod", "POST");
        apiRegister.put("pathTemplate", pathTemplate);
        apiRegister.put("requestSchemaJson", "{\"type\":\"object\",\"x-body-mode\":\"FORM_URLENCODED\"}");
        apiRegister.put("responseSchemaJson", "{\"type\":\"object\"}");
        apiRegister.put("operator", "tester");
        Map apiBody = post("/api/v1/tenant/" + tenant + "/connectors/" + connectorId + "/apis", apiRegister);
        Assertions.assertEquals(0, apiBody.get("code"));
    }

    private void createCapability(String tenant, String capabilityId) {
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("capabilityId", capabilityId);
        createRequest.put("displayName", "Office Leave Real");
        createRequest.put("domainCode", "OA");
        createRequest.put("operator", "tester");
        Map createBody = post("/api/v1/tenant/" + tenant + "/capabilities", createRequest);
        Assertions.assertEquals(0, createBody.get("code"));
    }

    private void publishCapability(String tenant, String capabilityId, int versionNo) {
        Map<String, Object> publishRequest = new HashMap<>();
        publishRequest.put("versionNo", versionNo);
        publishRequest.put("operator", "tester");
        Map publishBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/publish", publishRequest);
        Assertions.assertEquals(0, publishBody.get("code"));
    }

    private String property(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(toEnvKey(key));
        }
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(toEnvKey(key));
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("system property is required: " + key);
        }
        return value;
    }

    private String toEnvKey(String key) {
        return key.toUpperCase().replace('.', '_');
    }

    @SuppressWarnings("rawtypes")
    private Map post(String path, Object body) {
        String url = "http://localhost:" + port + path;
        ResponseEntity<Map> response = restTemplate.postForEntity(url, body, Map.class);
        return response.getBody();
    }

    @SuppressWarnings("rawtypes")
    private Map put(String path, Object body) {
        String url = "http://localhost:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body), Map.class);
        return response.getBody();
    }
}
