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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration test for SaaS capability flow.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.dashscope.api-key=dummy",
        "logging.level.com.alibaba.assistant.agent=INFO"
})
public class SaasFlowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Should execute DAG workflow with two steps when all slots are ready.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldExecuteDagWorkflowWhenCapabilityPublished() {
        String tenant = "t_dag_success";
        String capabilityId = "submit_leave_request";

        Number connectorId = createConnector(tenant, "office_oa_success");
        upsertConnectorAuth(tenant, connectorId.longValue());
        registerApi(tenant, connectorId.longValue(), "office_leave_add", "/home/leaves/add");
        registerApi(tenant, connectorId.longValue(), "office_leave_submit", "/api/check/submit_check");
        createCapability(tenant, capabilityId);

        Map<String, Object> versionRequest = new HashMap<>();
        versionRequest.put("connectorId", connectorId.longValue());
        versionRequest.put("inputSchemaJson", "{\"type\":\"object\",\"required\":[\"startDate\",\"endDate\",\"reason\"]}");
        versionRequest.put("slotSchemaJson", "{\"required\":[\"startDate\",\"endDate\",\"reason\"]}");
        versionRequest.put("outputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("routeConfigJson", "{\"nodes\":["
                + "{\"nodeCode\":\"leave_add\",\"apiCode\":\"office_leave_add\",\"requestBody\":{\"reason\":\"${input.reason}\"}},"
                + "{\"nodeCode\":\"leave_submit\",\"apiCode\":\"office_leave_submit\","
                + "\"requestBody\":{\"action_id\":\"${node.leave_add.data.action_id}\",\"reason\":\"${input.reason}\"}}"
                + "],\"edges\":[{\"from\":\"leave_add\",\"to\":\"leave_submit\"}]}");
        versionRequest.put("executionMode", "MUTATION");
        versionRequest.put("operator", "tester");
        Map versionBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/versions", versionRequest);
        Assertions.assertEquals(0, versionBody.get("code"));

        publishCapability(tenant, capabilityId, 1);

        Map<String, Object> chatRequest = new HashMap<>();
        chatRequest.put("requestId", "req-dag-1");
        chatRequest.put("executorUserId", "u-1");
        chatRequest.put("capabilityId", capabilityId);
        chatRequest.put("userInput", "help me create leave");
        chatRequest.put("input", Map.of("startDate", "2026-02-10 00:00", "endDate", "2026-02-10 00:04", "reason", "1"));
        Map executeBody = post("/api/v1/tenant/" + tenant + "/conversations/s1/chat", chatRequest);
        Assertions.assertEquals(0, executeBody.get("code"));
        Map data = (Map) executeBody.get("data");
        Assertions.assertEquals("DONE", data.get("status"));
        Map output = (Map) data.get("output");
        Map steps = (Map) output.get("steps");
        Assertions.assertTrue(steps.containsKey("leave_add"));
        Assertions.assertTrue(steps.containsKey("leave_submit"));
    }

    /**
     * Should recall capability candidates by semantic query.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldRecallCapabilityBySemanticQuery() {
        String tenant = "t_recall";
        String capabilityId = "leave_apply";

        Number connectorId = createConnector(tenant, "office_recall");
        upsertConnectorAuth(tenant, connectorId.longValue());
        registerApi(tenant, connectorId.longValue(), "office_leave_add_recall", "/home/leaves/add");
        createCapability(tenant, capabilityId, "请假申请");

        Map<String, Object> versionRequest = new HashMap<>();
        versionRequest.put("connectorId", connectorId.longValue());
        versionRequest.put("inputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("outputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("routeConfigJson", "{\"steps\":[{\"stepCode\":\"leave_add\",\"apiCode\":\"office_leave_add_recall\"}]}");
        versionRequest.put("executionMode", "MUTATION");
        versionRequest.put("operator", "tester");
        Map versionBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/versions", versionRequest);
        Assertions.assertEquals(0, versionBody.get("code"));
        publishCapability(tenant, capabilityId, 1);

        Map recallBody = get("/api/v1/tenant/" + tenant + "/capabilities/recall?query=我要请假&topK=3");
        Assertions.assertEquals(0, recallBody.get("code"));
        List recalls = (List) recallBody.get("data");
        Assertions.assertFalse(recalls.isEmpty());
        Map first = (Map) recalls.get(0);
        Assertions.assertEquals(capabilityId, first.get("capabilityId"));
    }

    /**
     * Should return collecting when slot values are missing, then done after supplement.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldCollectSlotsAcrossMultiTurnConversation() {
        String tenant = "t_slot_collect";
        String capabilityId = "submit_leave_collecting";

        Number connectorId = createConnector(tenant, "office_collect");
        upsertConnectorAuth(tenant, connectorId.longValue());
        registerApi(tenant, connectorId.longValue(), "office_leave_add_collect", "/home/leaves/add");
        registerApi(tenant, connectorId.longValue(), "office_leave_submit_collect", "/api/check/submit_check");
        createCapability(tenant, capabilityId);

        Map<String, Object> versionRequest = new HashMap<>();
        versionRequest.put("connectorId", connectorId.longValue());
        versionRequest.put("inputSchemaJson", "{\"type\":\"object\",\"required\":[\"startDate\",\"endDate\",\"reason\"]}");
        versionRequest.put("slotSchemaJson", "{\"required\":[\"startDate\",\"endDate\",\"reason\"]}");
        versionRequest.put("outputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("routeConfigJson", "{\"nodes\":["
                + "{\"nodeCode\":\"leave_add\",\"apiCode\":\"office_leave_add_collect\"},"
                + "{\"nodeCode\":\"leave_submit\",\"apiCode\":\"office_leave_submit_collect\","
                + "\"requestBody\":{\"action_id\":\"${node.leave_add.data.action_id}\"}"
                + "}],\"edges\":[{\"from\":\"leave_add\",\"to\":\"leave_submit\"}]}");
        versionRequest.put("executionMode", "MUTATION");
        versionRequest.put("operator", "tester");
        Map versionBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/versions", versionRequest);
        Assertions.assertEquals(0, versionBody.get("code"));

        publishCapability(tenant, capabilityId, 1);

        Map<String, Object> firstChat = new HashMap<>();
        firstChat.put("requestId", "req-slot-1");
        firstChat.put("executorUserId", "u-slot");
        firstChat.put("capabilityId", capabilityId);
        firstChat.put("userInput", "我要请假");
        firstChat.put("input", Map.of("reason", "family"));
        Map firstChatBody = post("/api/v1/tenant/" + tenant + "/conversations/s_slot/chat", firstChat);
        Assertions.assertEquals(0, firstChatBody.get("code"));
        Map firstData = (Map) firstChatBody.get("data");
        Assertions.assertEquals("COLLECTING", firstData.get("status"));
        List missingSlots = (List) firstData.get("missingSlots");
        Assertions.assertTrue(missingSlots.contains("startDate"));
        Assertions.assertTrue(missingSlots.contains("endDate"));

        Map<String, Object> secondChat = new HashMap<>();
        secondChat.put("requestId", "req-slot-2");
        secondChat.put("executorUserId", "u-slot");
        secondChat.put("capabilityId", capabilityId);
        secondChat.put("userInput", "补充时间");
        secondChat.put("input", Map.of("startDate", "2026-02-10 00:00", "endDate", "2026-02-10 00:04"));
        Map secondChatBody = post("/api/v1/tenant/" + tenant + "/conversations/s_slot/chat", secondChat);
        Assertions.assertEquals(0, secondChatBody.get("code"));
        Map secondData = (Map) secondChatBody.get("data");
        Assertions.assertEquals("DONE", secondData.get("status"));
    }

    /**
     * Should require binding for delegated user mode.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldRequireBindingForDelegatedMode() {
        String tenant = "t_delegated";
        String capabilityId = "submit_delegated_leave";

        Number connectorId = createConnector(tenant, "office");
        upsertConnectorAuth(tenant, connectorId.longValue());
        registerApi(tenant, connectorId.longValue(), "submit_leave_delegated", "/api/leave/delegated/submit");
        createCapability(tenant, capabilityId);

        Map<String, Object> versionRequest = new HashMap<>();
        versionRequest.put("connectorId", connectorId.longValue());
        versionRequest.put("inputSchemaJson", "{\"type\":\"object\",\"required\":[\"leaveType\"]}");
        versionRequest.put("slotSchemaJson", "{\"required\":[\"leaveType\"]}");
        versionRequest.put("outputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("routeConfigJson", "{\"steps\":[{\"stepCode\":\"submit\",\"apiCode\":\"submit_leave_delegated\"}]}");
        versionRequest.put("executionMode", "DELEGATED_USER");
        versionRequest.put("operator", "tester");
        Map versionBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/versions", versionRequest);
        Assertions.assertEquals(0, versionBody.get("code"));

        publishCapability(tenant, capabilityId, 1);

        Map<String, Object> firstChat = new HashMap<>();
        firstChat.put("requestId", "req-4a");
        firstChat.put("executorUserId", "u-delegated");
        firstChat.put("capabilityId", capabilityId);
        firstChat.put("userInput", "submit leave");
        firstChat.put("input", Map.of("leaveType", "annual"));
        Map firstChatBody = post("/api/v1/tenant/" + tenant + "/conversations/s4/chat", firstChat);
        Assertions.assertEquals(0, firstChatBody.get("code"));
        Map firstData = (Map) firstChatBody.get("data");
        Assertions.assertEquals("REJECTED", firstData.get("status"));
        Assertions.assertEquals("USER_BINDING_REQUIRED", firstData.get("errorCode"));

        Map<String, Object> bindingRequest = new HashMap<>();
        bindingRequest.put("platformUserId", "u-delegated");
        bindingRequest.put("systemCode", "office");
        bindingRequest.put("externalUserId", "u-office-1");
        bindingRequest.put("bindingMode", "DELEGATED_USER");
        bindingRequest.put("status", "ACTIVE");
        bindingRequest.put("operator", "tester");
        Map bindingBody = post("/api/v1/tenant/" + tenant + "/user-bindings", bindingRequest);
        Assertions.assertEquals(0, bindingBody.get("code"));

        Map<String, Object> secondChat = new HashMap<>();
        secondChat.put("requestId", "req-4b");
        secondChat.put("executorUserId", "u-delegated");
        secondChat.put("capabilityId", capabilityId);
        secondChat.put("userInput", "submit leave again");
        secondChat.put("input", Map.of("leaveType", "annual"));
        Map secondChatBody = post("/api/v1/tenant/" + tenant + "/conversations/s4/chat", secondChat);
        Assertions.assertEquals(0, secondChatBody.get("code"));
        Map secondData = (Map) secondChatBody.get("data");
        Assertions.assertEquals("DONE", secondData.get("status"));
    }

    /**
     * Should reject version creation when route step api is not registered.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void shouldRejectVersionCreateWhenRouteApiNotRegistered() {
        String tenant = "t_route_binding";
        String capabilityId = "leave_route_binding_check";

        Number connectorId = createConnector(tenant, "office_tool");
        createCapability(tenant, capabilityId);

        Map<String, Object> versionRequest = new HashMap<>();
        versionRequest.put("connectorId", connectorId.longValue());
        versionRequest.put("inputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("outputSchemaJson", "{\"type\":\"object\"}");
        versionRequest.put("routeConfigJson", "{\"steps\":[{\"stepCode\":\"submit\",\"apiCode\":\"unregistered_api\"}]}");
        versionRequest.put("executionMode", "MUTATION");
        versionRequest.put("operator", "tester");
        Map versionBody = post("/api/v1/tenant/" + tenant + "/capabilities/" + capabilityId + "/versions", versionRequest);
        Assertions.assertEquals(1, versionBody.get("code"));
        Assertions.assertEquals("route step api is not registered: unregistered_api", versionBody.get("msg"));
    }

    private Number createConnector(String tenant, String connectorCode) {
        Map<String, Object> connectorCreate = new HashMap<>();
        connectorCreate.put("connectorCode", connectorCode);
        connectorCreate.put("displayName", "Office OA");
        connectorCreate.put("connectorType", "MOCK");
        connectorCreate.put("baseUrl", "http://office.local");
        connectorCreate.put("operator", "tester");
        Map connectorCreateBody = post("/api/v1/tenant/" + tenant + "/connectors", connectorCreate);
        Assertions.assertEquals(0, connectorCreateBody.get("code"));
        return (Number) ((Map<?, ?>) connectorCreateBody.get("data")).get("id");
    }

    private void upsertConnectorAuth(String tenant, Long connectorId) {
        Map<String, Object> connectorAuth = new HashMap<>();
        connectorAuth.put("authType", "SESSION");
        connectorAuth.put("authConfigJson", "{\"sessionCookieName\":\"PHPSESSID\",\"sessionToken\":\"mock-session\"}");
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
        apiRegister.put("requestSchemaJson", "{\"type\":\"object\"}");
        apiRegister.put("responseSchemaJson", "{\"type\":\"object\"}");
        apiRegister.put("operator", "tester");
        Map apiBody = post("/api/v1/tenant/" + tenant + "/connectors/" + connectorId + "/apis", apiRegister);
        Assertions.assertEquals(0, apiBody.get("code"));
    }

    private void createCapability(String tenant, String capabilityId) {
        createCapability(tenant, capabilityId, capabilityId);
    }

    private void createCapability(String tenant, String capabilityId, String displayName) {
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("capabilityId", capabilityId);
        createRequest.put("displayName", displayName);
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

    @SuppressWarnings("rawtypes")
    private Map get(String path) {
        String url = "http://localhost:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        return response.getBody();
    }
}
