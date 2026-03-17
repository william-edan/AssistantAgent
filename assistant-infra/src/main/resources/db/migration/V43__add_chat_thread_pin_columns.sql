-- pinned: 布尔值，true 表示会话已置顶，false 或 NULL 表示未置顶
ALTER TABLE chat_thread
    ADD COLUMN pinned TINYINT(1) NULL DEFAULT 0 COMMENT '置顶标记：1-置顶，0-未置顶';
-- pinned_at: 记录置顶操作的时间，用于置顶列表的排序（新的置顶在前）
ALTER TABLE chat_thread
    ADD COLUMN pinned_at DATETIME NULL DEFAULT NULL COMMENT '置顶时间，用于置顶列表排序';

-- 索引1：用户置顶列表查询（按置顶时间降序）
-- 使用场景：查询用户的置顶会话列表
-- 覆盖查询：SELECT * FROM chat_thread WHERE assistant_uid = ? AND pinned = 1 ORDER BY pinned_at DESC
CREATE INDEX idx_chat_thread_assistant_pinned_at ON chat_thread (assistant_uid, pinned, pinned_at);

-- 索引2：普通会话列表查询（按更新时间降序）
-- 使用场景：查询用户的普通（非置顶）会话列表
-- 覆盖查询：SELECT * FROM chat_thread WHERE assistant_uid = ? AND (pinned = 0 OR pinned IS NULL) ORDER BY updated_at DESC
CREATE INDEX idx_chat_thread_assistant_normal ON chat_thread (assistant_uid, pinned, updated_at);




