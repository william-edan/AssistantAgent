-- 初始化勾股 OA 的 canonical 访问配置与用户可见业务工具。
-- 查询型 resolver 工具在 V28 中以 INTERNAL/DEPENDENCY_ONLY 方式单独注册。

INSERT INTO system_access_profile (
    system_code,
    base_url,
    token_endpoint,
    token_method,
    token_request_tpl,
    token_response_path,
    token_header_name,
    token_header_prefix,
    token_ttl_seconds,
    status
) VALUES (
    'gougu_oa',
    'http://office.ai.devefive.com',
    '/api/oa_integration/get_token',
    'POST',
    '{"system_user_id": "${system_user_id}"}',
    'data.token',
    'Authorization',
    'Bearer ',
    7200,
    'enabled'
)
ON DUPLICATE KEY UPDATE
    base_url = VALUES(base_url),
    token_endpoint = VALUES(token_endpoint),
    token_method = VALUES(token_method),
    token_request_tpl = VALUES(token_request_tpl),
    token_response_path = VALUES(token_response_path),
    token_header_name = VALUES(token_header_name),
    token_header_prefix = VALUES(token_header_prefix),
    token_ttl_seconds = VALUES(token_ttl_seconds),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO tool_meta (
    tenant_id,
    tool_code,
    tool_name,
    description,
    system_code,
    api_endpoint,
    http_method,
    content_type,
    parameter_schema,
    execution_plan,
    interaction_policy,
    risk_level,
    requires_auth,
    requires_confirm,
    capability_type,
    version,
    status
) VALUES
(
    'default',
    'gougu_oa.leave_application',
    '请假申请',
    '创建请假单并提交审批，默认推荐直属上级作为审批人，并把完整员工列表返回给前端二次确认。',
    'gougu_oa',
    '/home/leaves/add',
    'POST',
    'application/x-www-form-urlencoded',
    '{
      "slots": [
        {
          "name": "types",
          "type": "integer",
          "title": "请假类型",
          "aiHint": "请从用户输入中识别请假类型，必要时可结合请假原因推断。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "tool": {
              "toolCode": "gougu_oa.leave_type_options",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id"
            },
            "enumMapping": {
              "事假": 1,
              "年假": 2,
              "调休": 3,
              "调休假": 3,
              "病假": 4,
              "婚假": 5,
              "丧假": 6,
              "产假": 7,
              "陪产假": 8,
              "其他": 9
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 1,
            "showInSummary": true
          }
        },
        {
          "name": "start_date",
          "type": "string",
          "title": "开始日期",
          "aiHint": "请假开始日期，格式为 YYYY-MM-DD。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "date",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 2,
            "showInSummary": true
          }
        },
        {
          "name": "end_date",
          "type": "string",
          "title": "结束日期",
          "aiHint": "请假结束日期，格式为 YYYY-MM-DD，必须不早于开始日期。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "date",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 3,
            "showInSummary": true
          }
        },
        {
          "name": "reason",
          "type": "string",
          "title": "请假原因",
          "aiHint": "请保留用户原始请假原因，不要自行扩写。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "textarea",
          "displayConfig": {
            "summaryGroup": "SECONDARY",
            "summaryOrder": 5,
            "showInSummary": true
          }
        },
        {
          "name": "duration",
          "type": "number",
          "title": "请假时长",
          "aiHint": "系统会根据开始日期和结束日期自动计算时长。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "number",
          "computed": {
            "type": "FUNCTION",
            "function": "date_diff",
            "enabled": true,
            "defaultValue": 1,
            "params": {
              "start": "start_date",
              "end": "end_date",
              "unit": "days",
              "include_start": true,
              "include_end": true
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 4,
            "displaySuffix": "天",
            "showInSummary": true
          }
        },
        {
          "name": "check_flow_id",
          "type": "integer",
          "title": "审批流程",
          "aiHint": "系统自动选择可用审批流程。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "tool": {
              "toolCode": "gougu_oa.leave_flow_options",
              "resultPath": "data",
              "labelField": "title",
              "valueField": "flow_id"
            },
            "autoSelect": {
              "enabled": true,
              "when": "always",
              "strategy": "first"
            }
          },
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "check_uids",
          "type": "string",
          "title": "审批人",
          "aiHint": "系统默认推荐直属上级作为审批人，同时把完整员工列表返回给前端可修改。",
          "askMode": "AUTO",
          "required": false,
          "priority": "SUPPLEMENTARY",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "multiple": true,
            "tool": {
              "toolCode": "gougu_oa.approver_candidates",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id",
              "descriptionField": "department"
            },
            "autoSelect": {
              "enabled": true,
              "when": "always",
              "strategy": "first"
            }
          },
          "displayConfig": {
            "displayType": "avatar",
            "summaryGroup": "SECONDARY",
            "summaryOrder": 6,
            "showInSummary": true
          }
        },
        {
          "name": "check_copy_uids",
          "type": "array",
          "title": "抄送人",
          "aiHint": "抄送人可选，不主动要求用户填写。",
          "askMode": "AUTO",
          "required": false,
          "priority": "SUPPLEMENTARY",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "multiple": true,
            "tool": {
              "toolCode": "gougu_oa.user_directory",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id"
            }
          },
          "displayConfig": {
            "displayType": "avatar-list",
            "summaryGroup": "SECONDARY",
            "summaryOrder": 7,
            "showInSummary": true
          }
        }
      ]
    }',
    '{
      "version": "2.0",
      "entry": ["create_leave"],
      "terminal": ["submit_approval"],
      "steps": {
        "create_leave": {
          "stepId": "create_leave",
          "name": "创建请假记录",
          "type": "HTTP",
          "next": ["submit_approval"],
          "config": {
            "method": "POST",
            "endpoint": "/home/leaves/add",
            "contentType": "application/x-www-form-urlencoded",
            "inputMapping": {
              "id": "0",
              "types": "${types}",
              "reason": "${reason}",
              "duration": "${duration}",
              "end_date": "${end_date}",
              "file_ids": "",
              "start_date": "${start_date}"
            },
            "outputMapping": {
              "message": "$.msg",
              "leave_id": "$.data.return_id"
            },
            "successCondition": "$.code == 0"
          }
        },
        "submit_approval": {
          "stepId": "submit_approval",
          "name": "提交审批",
          "type": "HTTP",
          "dependsOn": ["create_leave"],
          "config": {
            "method": "POST",
            "endpoint": "/api/check/submit_check",
            "contentType": "application/x-www-form-urlencoded",
            "inputMapping": {
              "flow_id": "${check_flow_id}",
              "action_id": "${create_leave.leave_id}",
              "check_name": "leaves",
              "check_uids": "${check_uids}",
              "check_copy_uids": "${check_copy_uids}"
            },
            "outputMapping": {
              "code": "$.code",
              "final_message": "$.msg"
            },
            "successCondition": "$.code == 0"
          }
        }
      }
    }',
    '{
      "toolType": "WORKFLOW",
      "visibility": "USER",
      "invocationPolicy": "DIRECT",
      "executionMode": "SYNC",
      "collect": {
        "batch_size": 4,
        "max_rounds": 5,
        "collect_order": "priority_first",
        "skip_collected": true
      },
      "confirm": {
        "enabled": true,
        "allow_edit": true,
        "show_all_params": true,
        "confirmation_prompt": "请确认以下请假信息，如需修改请直接说明。确认无误请回复【确认】或【提交】"
      },
      "execute": {
        "auto_execute": false,
        "max_retries": 0,
        "retry_on_fail": false
      }
    }',
    'MEDIUM',
    1,
    1,
    'WORKFLOW',
    1,
    'enabled'
),
(
    'default',
    'gougu_oa.meeting_room_booking',
    '会议室预定',
    '创建会议室预定并提交审批，默认推荐直属上级作为审批人，前端可按员工目录重新选择。',
    'gougu_oa',
    '/adm/meeting/add',
    'POST',
    'application/x-www-form-urlencoded',
    '{
      "slots": [
        {
          "name": "room_id",
          "type": "integer",
          "title": "会议室",
          "aiHint": "请选择可用会议室。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "tool": {
              "toolCode": "gougu_oa.meeting_room_options",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id"
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 1,
            "showInSummary": true
          }
        },
        {
          "name": "title",
          "type": "string",
          "title": "会议主题",
          "aiHint": "会议主题应尽量简洁明确。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "text",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 2,
            "showInSummary": true
          }
        },
        {
          "name": "start_date",
          "type": "string",
          "title": "开始时间",
          "aiHint": "会议开始时间，格式为 YYYY-MM-DD HH:mm。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "datetime",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 3,
            "showInSummary": true
          }
        },
        {
          "name": "end_date",
          "type": "string",
          "title": "结束时间",
          "aiHint": "会议结束时间必须晚于开始时间。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "datetime",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 4,
            "showInSummary": true
          }
        },
        {
          "name": "num",
          "type": "integer",
          "title": "会议人数",
          "aiHint": "请记录参会人数。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "number",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 5,
            "showInSummary": true
          }
        },
        {
          "name": "requirement",
          "type": "array",
          "title": "会议需求",
          "aiHint": "可按需选择投影、电子屏等会议需求。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "checkbox",
          "options": {
            "source": "TOOL",
            "multiple": true,
            "tool": {
              "toolCode": "gougu_oa.meeting_requirement_options",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id"
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 6,
            "showInSummary": true
          }
        },
        {
          "name": "join_uids",
          "type": "array",
          "title": "参会人员",
          "aiHint": "参会人员可从员工目录中选择。",
          "askMode": "AUTO",
          "required": false,
          "priority": "SUPPLEMENTARY",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "multiple": true,
            "tool": {
              "toolCode": "gougu_oa.user_directory",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id"
            }
          },
          "displayConfig": {
            "displayType": "avatar-list",
            "summaryGroup": "SECONDARY",
            "summaryOrder": 7,
            "showInSummary": true
          }
        },
        {
          "name": "remark",
          "type": "string",
          "title": "会议说明",
          "aiHint": "会议说明可选，用于补充上下文。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "textarea",
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "check_flow_id",
          "type": "integer",
          "title": "审批流程",
          "aiHint": "系统自动选择适用审批流程。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "tool": {
              "toolCode": "gougu_oa.meeting_flow_options",
              "resultPath": "data",
              "labelField": "title",
              "valueField": "flow_id"
            },
            "autoSelect": {
              "enabled": true,
              "when": "always",
              "strategy": "first"
            }
          },
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "check_uids",
          "type": "string",
          "title": "审批人",
          "aiHint": "系统默认推荐直属上级作为审批人，同时返回完整员工列表供前端修改。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "tool": {
              "toolCode": "gougu_oa.approver_candidates",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id",
              "descriptionField": "department"
            },
            "autoSelect": {
              "enabled": true,
              "when": "always",
              "strategy": "first"
            }
          },
          "displayConfig": {
            "displayType": "avatar",
            "summaryGroup": "SECONDARY",
            "summaryOrder": 8,
            "showInSummary": true
          }
        }
      ]
    }',
    '{
      "version": "2.0",
      "entry": ["create_booking"],
      "terminal": ["submit_approval"],
      "steps": {
        "create_booking": {
          "stepId": "create_booking",
          "name": "创建会议室预定记录",
          "type": "HTTP",
          "next": ["submit_approval"],
          "config": {
            "method": "POST",
            "endpoint": "/adm/meeting/add",
            "contentType": "application/x-www-form-urlencoded",
            "inputMapping": {
              "id": "0",
              "num": "${num}",
              "title": "${title}",
              "remark": "${remark}",
              "room_id": "${room_id}",
              "end_date": "${end_date}",
              "join_uids": "${join_uids}",
              "start_date": "${start_date}",
              "requirement": "${requirement}"
            },
            "outputMapping": {
              "message": "$.msg",
              "booking_id": "$.data.return_id"
            },
            "successCondition": "$.code == 0"
          }
        },
        "submit_approval": {
          "stepId": "submit_approval",
          "name": "提交审批",
          "type": "HTTP",
          "dependsOn": ["create_booking"],
          "config": {
            "method": "POST",
            "endpoint": "/api/check/submit_check",
            "contentType": "application/x-www-form-urlencoded",
            "inputMapping": {
              "flow_id": "${check_flow_id}",
              "action_id": "${create_booking.booking_id}",
              "check_name": "meeting_order",
              "check_uids": "${check_uids}",
              "check_copy_uids": ""
            },
            "outputMapping": {
              "code": "$.code",
              "final_message": "$.msg"
            },
            "successCondition": "$.code == 0"
          }
        }
      }
    }',
    '{
      "toolType": "WORKFLOW",
      "visibility": "USER",
      "invocationPolicy": "DIRECT",
      "executionMode": "SYNC",
      "collect": {
        "batch_size": 4,
        "max_rounds": 5,
        "collect_order": "priority_first",
        "skip_collected": true
      },
      "confirm": {
        "enabled": true,
        "allow_edit": true,
        "show_all_params": true,
        "confirmation_prompt": "请确认以下会议室预定信息，如需修改请直接说明。确认无误请回复【确认】或【提交】"
      },
      "execute": {
        "auto_execute": false,
        "max_retries": 0,
        "retry_on_fail": false
      }
    }',
    'MEDIUM',
    1,
    1,
    'WORKFLOW',
    1,
    'enabled'
),
(
    'default',
    'gougu_oa.work_report',
    '工作汇报',
    '创建工作日报、周报或月报，并支持在确认卡中调整接收人后提交。',
    'gougu_oa',
    '/oa/work/add',
    'POST',
    'application/x-www-form-urlencoded',
    '{
      "slots": [
        {
          "name": "types",
          "type": "integer",
          "title": "汇报类型",
          "aiHint": "先确认汇报类型，再按类型自动推导本期时间范围。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "select",
          "options": {
            "source": "ENUM",
            "enumMapping": {
              "日报": 1,
              "周报": 2,
              "月报": 3
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 1,
            "showInSummary": true
          }
        },
        {
          "name": "start_date",
          "type": "string",
          "title": "开始日期",
          "aiHint": "在汇报类型确定后自动推导。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "date",
          "dependsOn": ["types"],
          "computed": {
            "type": "FUNCTION",
            "function": "period_preset",
            "enabled": true,
            "params": {
              "selector": "types",
              "target": "start",
              "anchor": "current_date",
              "presets": {
                "1": "DAY",
                "2": "WEEK",
                "3": "MONTH"
              }
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 2,
            "showInSummary": true
          }
        },
        {
          "name": "end_date",
          "type": "string",
          "title": "结束日期",
          "aiHint": "在汇报类型确定后自动推导。",
          "askMode": "AUTO",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "date",
          "dependsOn": ["types"],
          "computed": {
            "type": "FUNCTION",
            "function": "period_preset",
            "enabled": true,
            "params": {
              "selector": "types",
              "target": "end",
              "anchor": "current_date",
              "presets": {
                "1": "DAY",
                "2": "WEEK",
                "3": "MONTH"
              }
            }
          },
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 3,
            "showInSummary": true
          }
        },
        {
          "name": "range_date",
          "type": "string",
          "title": "汇报日期范围",
          "aiHint": "系统根据开始和结束日期自动生成展示文案。",
          "askMode": "NEVER",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "text",
          "dependsOn": ["types"],
          "submit": false,
          "computed": {
            "type": "FUNCTION",
            "function": "date_range_label",
            "enabled": true,
            "params": {
              "start": "start_date",
              "end": "end_date",
              "collapse_same_day": true
            }
          },
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "submit_range_date",
          "type": "string",
          "title": "提交日期范围",
          "aiHint": "根据汇报类型生成提交给企业系统的日期范围字段，日报留空，周报和月报提交汇报周期。",
          "askMode": "NEVER",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "hidden",
          "dependsOn": ["types"],
          "submit": false,
          "computed": {
            "type": "FUNCTION",
            "function": "selector_switch",
            "enabled": true,
            "params": {
              "selector": "types",
              "cases": {
                "1": "",
                "2": "range_date",
                "3": "range_date"
              },
              "default": ""
            }
          },
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "works",
          "type": "string",
          "title": "工作内容",
          "aiHint": "记录用户已经完成的工作内容，不要自行补充。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "textarea",
          "displayConfig": {
            "summaryGroup": "SECONDARY",
            "summaryOrder": 4,
            "showInSummary": true
          }
        },
        {
          "name": "plans",
          "type": "string",
          "title": "工作计划",
          "aiHint": "下期工作计划可选。",
          "askMode": "AUTO",
          "required": false,
          "priority": "SUPPLEMENTARY",
          "uiComponent": "textarea",
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "remark",
          "type": "string",
          "title": "其他事项",
          "aiHint": "其他事项可选。",
          "askMode": "AUTO",
          "required": false,
          "priority": "SUPPLEMENTARY",
          "uiComponent": "textarea",
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "send",
          "type": "integer",
          "title": "发送方式",
          "aiHint": "0 表示仅保存，1 表示保存并发送。未明确时默认仅保存。",
          "askMode": "FORM_ONLY",
          "required": false,
          "priority": "OPTIONAL",
          "uiComponent": "radio",
          "defaultValue": 0,
          "options": {
            "source": "ENUM",
            "enumMapping": {
              "仅保存": 0,
              "保存并发送": 1
            }
          },
          "displayConfig": {
            "showInSummary": false
          }
        },
        {
          "name": "to_uids",
          "type": "string",
          "title": "接收人",
          "aiHint": "接收人从员工目录中选择，默认可预选首个候选人。",
          "askMode": "AUTO",
          "required": true,
          "priority": "SUPPLEMENTARY",
          "uiComponent": "select",
          "options": {
            "source": "TOOL",
            "multiple": true,
            "tool": {
              "toolCode": "gougu_oa.user_directory",
              "resultPath": "data",
              "labelField": "name",
              "valueField": "id"
            },
            "autoSelect": {
              "enabled": true,
              "when": "always",
              "strategy": "first"
            }
          },
          "displayConfig": {
            "displayType": "avatar-list",
            "summaryGroup": "SECONDARY",
            "summaryOrder": 5,
            "showInSummary": true
          }
        }
      ]
    }',
    '{
      "version": "2.0",
      "entry": ["create_and_send_report"],
      "terminal": ["create_and_send_report"],
      "steps": {
        "create_and_send_report": {
          "stepId": "create_and_send_report",
          "name": "创建并发送工作汇报",
          "type": "HTTP",
          "config": {
            "method": "POST",
            "endpoint": "/oa/work/add",
            "contentType": "application/x-www-form-urlencoded",
            "inputMapping": {
              "id": "0",
              "file": "",
              "send": "${send}",
              "plans": "${plans}",
              "types": "${types}",
              "works": "${works}",
              "remark": "${remark}",
              "to_uids": "${to_uids}",
              "file_ids": "",
              "start_date": "${start_date}",
              "end_date": "${end_date}",
              "range_date": "${submit_range_date}"
            },
            "outputMapping": {
              "message": "$.msg",
              "report_id": "$.data.id"
            },
            "successCondition": "$.code == 0"
          }
        }
      }
    }',
    '{
      "toolType": "ACTION",
      "visibility": "USER",
      "invocationPolicy": "DIRECT",
      "executionMode": "SYNC",
      "collect": {
        "batch_size": 3,
        "max_rounds": 5,
        "collect_order": "priority_first",
        "skip_collected": true
      },
      "confirm": {
        "enabled": true,
        "allow_edit": true,
        "show_all_params": true,
        "confirmation_prompt": "请确认以下工作汇报信息，如需修改请直接说明。确认无误请回复【确认】或【提交】"
      },
      "execute": {
        "auto_execute": false,
        "max_retries": 0,
        "retry_on_fail": false
      }
    }',
    'LOW',
    1,
    1,
    'ACTION',
    1,
    'enabled'
)
ON DUPLICATE KEY UPDATE
    tool_name = VALUES(tool_name),
    description = VALUES(description),
    system_code = VALUES(system_code),
    api_endpoint = VALUES(api_endpoint),
    http_method = VALUES(http_method),
    content_type = VALUES(content_type),
    parameter_schema = VALUES(parameter_schema),
    execution_plan = VALUES(execution_plan),
    interaction_policy = VALUES(interaction_policy),
    risk_level = VALUES(risk_level),
    requires_auth = VALUES(requires_auth),
    requires_confirm = VALUES(requires_confirm),
    capability_type = VALUES(capability_type),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;
