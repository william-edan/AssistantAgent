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
package com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Connector API definition DO.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@TableName("assistant_connector_api")
public class ConnectorApiDO extends BaseTenantDO {

    @TableField("connector_id")
    private Long connectorId;

    @TableField("api_code")
    private String apiCode;

    @TableField("display_name")
    private String displayName;

    @TableField("http_method")
    private String httpMethod;

    @TableField("path_template")
    private String pathTemplate;

    @TableField("request_schema_json")
    private String requestSchemaJson;

    @TableField("response_schema_json")
    private String responseSchemaJson;

    @TableField("status")
    private String status;

    public Long getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(Long connectorId) {
        this.connectorId = connectorId;
    }

    public String getApiCode() {
        return apiCode;
    }

    public void setApiCode(String apiCode) {
        this.apiCode = apiCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPathTemplate() {
        return pathTemplate;
    }

    public void setPathTemplate(String pathTemplate) {
        this.pathTemplate = pathTemplate;
    }

    public String getRequestSchemaJson() {
        return requestSchemaJson;
    }

    public void setRequestSchemaJson(String requestSchemaJson) {
        this.requestSchemaJson = requestSchemaJson;
    }

    public String getResponseSchemaJson() {
        return responseSchemaJson;
    }

    public void setResponseSchemaJson(String responseSchemaJson) {
        this.responseSchemaJson = responseSchemaJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
