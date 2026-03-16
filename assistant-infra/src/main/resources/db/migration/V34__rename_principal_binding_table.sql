-- 去掉历史版本后缀，统一主体绑定主表命名。
RENAME TABLE principal_binding_v2 TO principal_binding;

ALTER TABLE principal_binding
    COMMENT = '主体到外部身份绑定表';
