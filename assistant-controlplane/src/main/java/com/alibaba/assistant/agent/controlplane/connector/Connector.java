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
package com.alibaba.assistant.agent.controlplane.connector;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 连接器实体。
 *
 * <p>用于描述某个企业系统如何接入平台，例如系统编码、访问地址、协议类型、网络区域和版本信息。</p>
 */
@TableName("connector")
public class Connector {

	/** 主键 ID。 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 所属空间 ID。 */
	private Long spaceId;

	/** 连接器编码，平台内部唯一。 */
	private String connectorCode;

	/** 外部系统编码，例如 gougu_oa。 */
	private String systemCode;

	/** 给管理界面和运维看的展示名称。 */
	private String displayName;

	/** 协议类型，例如 HTTP、MCP、BROWSER。 */
	private String protocolType;

	/** 所属环境，例如 prod、test。 */
	private String environment;

	/** 网络区域，用于区分内外网或专线。 */
	private String networkZone;

	/** 目标系统基础地址。 */
	private String baseUrl;

	/** 当前状态。 */
	private String status;

	/** 配置版本号。 */
	private Integer version;

	/** 创建时间。 */
	private LocalDateTime createdAt;

	/** 更新时间。 */
	private LocalDateTime updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getSpaceId() {
		return spaceId;
	}

	public void setSpaceId(Long spaceId) {
		this.spaceId = spaceId;
	}

	public String getConnectorCode() {
		return connectorCode;
	}

	public void setConnectorCode(String connectorCode) {
		this.connectorCode = connectorCode;
	}

	public String getSystemCode() {
		return systemCode;
	}

	public void setSystemCode(String systemCode) {
		this.systemCode = systemCode;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getProtocolType() {
		return protocolType;
	}

	public void setProtocolType(String protocolType) {
		this.protocolType = protocolType;
	}

	public String getEnvironment() {
		return environment;
	}

	public void setEnvironment(String environment) {
		this.environment = environment;
	}

	public String getNetworkZone() {
		return networkZone;
	}

	public void setNetworkZone(String networkZone) {
		this.networkZone = networkZone;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
