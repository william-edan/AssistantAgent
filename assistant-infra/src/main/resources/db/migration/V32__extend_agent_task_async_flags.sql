ALTER TABLE agent_task
    ADD COLUMN background TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否后台任务' AFTER collapsible,
    ADD COLUMN detached TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否与主对话分离展示' AFTER background;
