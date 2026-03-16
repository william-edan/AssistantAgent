package com.alibaba.assistant.agent.api.protocol;

/**
 * 聊天工作流对前端暴露的稳定阶段枚举。
 */
public enum FrontendStage {

	UNDERSTANDING,

	COLLECTING,

	CONFIRMING,

	EXECUTING,

	WAITING_APPROVAL,

	DONE,

	ERROR

}
