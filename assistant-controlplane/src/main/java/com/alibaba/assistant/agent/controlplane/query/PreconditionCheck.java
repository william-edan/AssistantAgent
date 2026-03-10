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
package com.alibaba.assistant.agent.controlplane.query;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("precondition_check")
public class PreconditionCheck {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long spaceId;
	private String checkCode;
	private Long connectorId;
	private String operationBindingJson;
	private String allowedAuthProfilesJson;
	private String bindingStrategiesJson;
	private String inputSchemaJson;
	private String checkExpressionJson;
	private String failurePolicyJson;
	private String status;
	private Integer version;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getSpaceId() { return spaceId; }
	public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
	public String getCheckCode() { return checkCode; }
	public void setCheckCode(String checkCode) { this.checkCode = checkCode; }
	public Long getConnectorId() { return connectorId; }
	public void setConnectorId(Long connectorId) { this.connectorId = connectorId; }
	public String getOperationBindingJson() { return operationBindingJson; }
	public void setOperationBindingJson(String operationBindingJson) { this.operationBindingJson = operationBindingJson; }
	public String getAllowedAuthProfilesJson() { return allowedAuthProfilesJson; }
	public void setAllowedAuthProfilesJson(String allowedAuthProfilesJson) { this.allowedAuthProfilesJson = allowedAuthProfilesJson; }
	public String getBindingStrategiesJson() { return bindingStrategiesJson; }
	public void setBindingStrategiesJson(String bindingStrategiesJson) { this.bindingStrategiesJson = bindingStrategiesJson; }
	public String getInputSchemaJson() { return inputSchemaJson; }
	public void setInputSchemaJson(String inputSchemaJson) { this.inputSchemaJson = inputSchemaJson; }
	public String getCheckExpressionJson() { return checkExpressionJson; }
	public void setCheckExpressionJson(String checkExpressionJson) { this.checkExpressionJson = checkExpressionJson; }
	public String getFailurePolicyJson() { return failurePolicyJson; }
	public void setFailurePolicyJson(String failurePolicyJson) { this.failurePolicyJson = failurePolicyJson; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public Integer getVersion() { return version; }
	public void setVersion(Integer version) { this.version = version; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
