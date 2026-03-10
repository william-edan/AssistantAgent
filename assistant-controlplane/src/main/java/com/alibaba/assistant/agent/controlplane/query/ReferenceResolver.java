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

@TableName("reference_resolver")
public class ReferenceResolver {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long spaceId;
	private String resolverCode;
	private Long connectorId;
	private String operationBindingJson;
	private String allowedAuthProfilesJson;
	private String inputSchemaJson;
	private String outputSchemaJson;
	private String cachePolicyJson;
	private String stalenessPolicyJson;
	private String visibility;
	private String status;
	private Integer version;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getSpaceId() { return spaceId; }
	public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
	public String getResolverCode() { return resolverCode; }
	public void setResolverCode(String resolverCode) { this.resolverCode = resolverCode; }
	public Long getConnectorId() { return connectorId; }
	public void setConnectorId(Long connectorId) { this.connectorId = connectorId; }
	public String getOperationBindingJson() { return operationBindingJson; }
	public void setOperationBindingJson(String operationBindingJson) { this.operationBindingJson = operationBindingJson; }
	public String getAllowedAuthProfilesJson() { return allowedAuthProfilesJson; }
	public void setAllowedAuthProfilesJson(String allowedAuthProfilesJson) { this.allowedAuthProfilesJson = allowedAuthProfilesJson; }
	public String getInputSchemaJson() { return inputSchemaJson; }
	public void setInputSchemaJson(String inputSchemaJson) { this.inputSchemaJson = inputSchemaJson; }
	public String getOutputSchemaJson() { return outputSchemaJson; }
	public void setOutputSchemaJson(String outputSchemaJson) { this.outputSchemaJson = outputSchemaJson; }
	public String getCachePolicyJson() { return cachePolicyJson; }
	public void setCachePolicyJson(String cachePolicyJson) { this.cachePolicyJson = cachePolicyJson; }
	public String getStalenessPolicyJson() { return stalenessPolicyJson; }
	public void setStalenessPolicyJson(String stalenessPolicyJson) { this.stalenessPolicyJson = stalenessPolicyJson; }
	public String getVisibility() { return visibility; }
	public void setVisibility(String visibility) { this.visibility = visibility; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public Integer getVersion() { return version; }
	public void setVersion(Integer version) { this.version = version; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
