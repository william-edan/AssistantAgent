$ErrorActionPreference = 'Stop'

$routePath = 'D:\phpstudy_pro\WWW\office1\route\app.php'
$integrationPath = 'D:\phpstudy_pro\WWW\office1\app\api\controller\OaIntegration.php'
$checkPath = 'D:\phpstudy_pro\WWW\office1\app\api\controller\Check.php'

$routeContent = Get-Content $routePath -Raw
$routeInsert = @"
    Route::post('book_meeting', 'api.OaIntegration/book_meeting'); // 创建会议预订
    Route::post('apply_leave', 'api.OaIntegration/apply_leave'); // 创建请假申请
    Route::post('assign_leave_handover', 'api.OaIntegration/assign_leave_handover'); // 安排请假交接
    Route::post('submit_weekly_report', 'api.OaIntegration/submit_weekly_report'); // 创建并发送周报
    Route::get('get_pending_approvals', 'api.OaIntegration/get_pending_approvals'); // 获取待处理审批
    Route::post('remind_approval', 'api.OaIntegration/remind_approval'); // 催办审批
    Route::post('escalate_approval', 'api.OaIntegration/escalate_approval'); // 升级审批

"@
if ($routeContent -notmatch "Route::post\('book_meeting'") {
    $routeContent = $routeContent.Replace("    // 测试接口`r`n", $routeInsert + "    // 测试接口`r`n")
    Set-Content -Path $routePath -Value $routeContent -Encoding UTF8
}

$integrationContent = Get-Content $integrationPath -Raw

$leaderMethod = @"
    /**
     * 获取当前用户的上级领导
     * 用于请假审批时自动选择审批人
     *
     * @return \think\Response
     */
    public function get_my_leader()
    {
        try {
            $userInfo = $this->verifyToken();
            if ($userInfo['code'] !== 0) {
                return to_assign($userInfo['code'], $userInfo['msg']);
            }

            $leaders = $this->getLeaderCandidatesByUid(intval($userInfo['data']['uid']));
            if (empty($leaders)) {
                return to_assign(0, '成功', []);
            }

            $primaryLeader = $leaders[0];
            $primaryLeader['candidates'] = $leaders;
            return to_assign(0, '成功', $primaryLeader);
        } catch (\think\exception\HttpResponseException $e) {
            throw $e;
        } catch (\Exception $e) {
            return to_assign(1, '获取上级领导失败: ' . $e->getMessage());
        }
    }

"@

$integrationContent = [regex]::Replace(
    $integrationContent,
    '(?s)\s*/\*\*\s*\r?\n\s*\* 获取当前用户的上级领导.*?public function get_my_leader\(\).*?^\s*}\r?\n\r?\n',
    "`r`n$leaderMethod",
    [System.Text.RegularExpressions.RegexOptions]::Multiline
)

$actionBlock = @"
    /**
     * 创建会议预订
     *
     * @return \think\Response
     */
    public function book_meeting()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $title = trim(strval($param['title'] ?? ''));
        $roomId = intval($param['room_id'] ?? ($param['roomId'] ?? 0));
        $startRaw = $param['start_date'] ?? ($param['startAt'] ?? '');
        $endRaw = $param['end_date'] ?? ($param['endAt'] ?? '');
        $remark = trim(strval($param['remark'] ?? ''));

        if ($roomId <= 0) {
            return to_assign(1, '会议室不能为空');
        }
        if ($title === '') {
            return to_assign(1, '会议主题不能为空');
        }

        $startTimestamp = $this->normalizeDateTimeValue($startRaw, false);
        $endTimestamp = $this->normalizeDateTimeValue($endRaw, true);
        if ($startTimestamp <= 0 || $endTimestamp <= 0) {
            return to_assign(1, '会议开始时间和结束时间不能为空');
        }
        if ($endTimestamp <= $startTimestamp) {
            return to_assign(1, '结束时间需要大于开始时间');
        }

        $room = Db::name('MeetingRoom')
            ->where('id', $roomId)
            ->where('status', 1)
            ->find();
        if (empty($room)) {
            return to_assign(1, '会议室不存在或已停用');
        }

        $hasConflict = Db::name('MeetingOrder')
            ->where('delete_time', 0)
            ->where('room_id', $roomId)
            ->where('start_date', '<', $endTimestamp)
            ->where('end_date', '>', $startTimestamp)
            ->count();
        if ($hasConflict > 0) {
            return to_assign(1, '您所选的时间区间已有预定记录，请重新选时间');
        }

        $requirementIds = $this->normalizeMeetingRequirements($param['requirement'] ?? ($param['requirements'] ?? []));
        $attendeeIds = $this->normalizeIdList($param['attendeeIds'] ?? ($param['attendee_ids'] ?? []));
        $num = intval($param['num'] ?? 0);
        if ($num <= 0 && !empty($attendeeIds)) {
            $num = count($attendeeIds);
        }
        if ($num <= 0) {
            $num = 1;
        }

        $remarkParts = [];
        if ($remark !== '') {
            $remarkParts[] = $remark;
        }
        if (!empty($attendeeIds)) {
            $remarkParts[] = '参会人ID: ' . implode(',', $attendeeIds);
        }

        $insertData = [
            'title' => $title,
            'room_id' => $roomId,
            'start_date' => $startTimestamp,
            'end_date' => $endTimestamp,
            'requirements' => implode(',', $requirementIds),
            'num' => $num,
            'remark' => implode('；', $remarkParts),
            'admin_id' => intval($userInfo['data']['uid']),
            'did' => intval($userInfo['data']['did'] ?? 0),
            'create_time' => time(),
        ];

        $meetingId = Db::name('MeetingOrder')->strict(false)->field(true)->insertGetId($insertData);
        add_log('add', $meetingId, $insertData, '会议室预订');

        return to_assign(0, '预订成功', [
            'meetingId' => (string)$meetingId,
            'roomId' => (string)$roomId,
        ]);
    }

    /**
     * 创建请假申请
     *
     * @return \think\Response
     */
    public function apply_leave()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $types = trim(strval($param['types'] ?? ($param['leaveType'] ?? '')));
        $startRaw = $param['start_date'] ?? ($param['startDate'] ?? '');
        $endRaw = $param['end_date'] ?? ($param['endDate'] ?? '');
        $reason = trim(strval($param['reason'] ?? ''));

        if ($types === '') {
            return to_assign(1, '请假类型不能为空');
        }
        if ($reason === '') {
            return to_assign(1, '请假原因不能为空');
        }

        $startTimestamp = $this->normalizeDateTimeValue($startRaw, false);
        $endTimestamp = $this->normalizeDateTimeValue($endRaw, true);
        if ($startTimestamp <= 0 || $endTimestamp <= 0) {
            return to_assign(1, '开始日期和结束日期不能为空');
        }
        if ($endTimestamp < $startTimestamp) {
            return to_assign(1, '结束时间不能小于开始时间');
        }

        $insertData = [
            'types' => $types,
            'start_date' => $startTimestamp,
            'end_date' => $endTimestamp,
            'duration' => $this->calculateLeaveDuration($startTimestamp, $endTimestamp),
            'reason' => $reason,
            'file_ids' => '',
            'admin_id' => intval($userInfo['data']['uid']),
            'did' => intval($userInfo['data']['did'] ?? 0),
            'create_time' => time(),
        ];

        $leaveId = Db::name('Leaves')->strict(false)->field(true)->insertGetId($insertData);
        add_log('add', $leaveId, $insertData, '请假申请');

        return to_assign(0, '提交成功', [
            'leaveId' => (string)$leaveId,
        ]);
    }

    /**
     * 安排请假交接
     *
     * @return \think\Response
     */
    public function assign_leave_handover()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $leaveId = intval($param['leave_id'] ?? ($param['leaveId'] ?? 0));
        $handoverUid = intval($param['handover_uid'] ?? ($param['handoverUserId'] ?? 0));
        $handoverNote = trim(strval($param['handover_note'] ?? ($param['handoverNote'] ?? '')));

        if ($leaveId <= 0) {
            return to_assign(1, '请假单ID不能为空');
        }
        if ($handoverUid <= 0) {
            return to_assign(1, '交接人不能为空');
        }

        $leave = Db::name('Leaves')
            ->where('id', $leaveId)
            ->where('delete_time', 0)
            ->find();
        if (empty($leave)) {
            return to_assign(1, '请假单不存在');
        }
        if (intval($leave['admin_id']) !== intval($userInfo['data']['uid'])) {
            return to_assign(1, '只能为自己的请假单安排交接');
        }

        $handoverUser = Db::name('Admin')
            ->where('id', $handoverUid)
            ->where('status', 1)
            ->field('id,name')
            ->find();
        if (empty($handoverUser)) {
            return to_assign(1, '交接人不存在或已禁用');
        }

        $title = '请假交接安排';
        $content = '请协助处理请假期间的值班交接';
        if ($handoverNote !== '') {
            $content .= '：' . $handoverNote;
        }
        $handoverId = $this->insertDirectMessages([$handoverUid], $leaveId, $title, $content);
        add_log('handover', $leaveId, [
            'handover_uid' => $handoverUid,
            'handover_note' => $handoverNote,
        ], '请假交接安排');

        return to_assign(0, '交接安排成功', [
            'handoverId' => (string)$handoverId,
        ]);
    }

    /**
     * 创建并发送周报
     *
     * @return \think\Response
     */
    public function submit_weekly_report()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $types = $this->normalizeWorkType($param['types'] ?? ($param['reportType'] ?? ''));
        $startRaw = $param['start_date'] ?? ($param['startDate'] ?? '');
        $endRaw = $param['end_date'] ?? ($param['endDate'] ?? '');
        $works = trim(strval($param['works'] ?? ($param['content'] ?? '')));
        $plans = trim(strval($param['plans'] ?? ''));
        $remark = trim(strval($param['remark'] ?? ''));
        $recipientIds = $this->normalizeIdList($param['to_uids'] ?? ($param['recipientIds'] ?? []));
        $send = intval($param['send'] ?? 1);

        if ($types <= 0) {
            return to_assign(1, '汇报类型不能为空');
        }
        if ($works === '') {
            return to_assign(1, '工作内容不能为空');
        }
        if (empty($recipientIds)) {
            return to_assign(1, '接收人不能为空');
        }

        $startTimestamp = $this->normalizeDateValue($startRaw, false);
        $endTimestamp = $this->normalizeDateValue($endRaw, false);
        if ($startTimestamp <= 0 || $endTimestamp <= 0) {
            return to_assign(1, '汇报周期不能为空');
        }
        if ($endTimestamp < $startTimestamp) {
            return to_assign(1, '结束日期不能小于开始日期');
        }

        $insertData = [
            'types' => $types,
            'start_date' => $startTimestamp,
            'end_date' => $endTimestamp,
            'works' => $works,
            'plans' => $plans,
            'remark' => $remark,
            'to_uids' => implode(',', $recipientIds),
            'send' => $send,
            'file_ids' => '',
            'admin_id' => intval($userInfo['data']['uid']),
            'create_time' => time(),
        ];

        $reportId = Db::name('Work')->strict(false)->field(true)->insertGetId($insertData);
        add_log('add', $reportId, $insertData, '工作汇报');

        if ($send === 1) {
            $sendTime = time();
            $records = [];
            foreach ($recipientIds as $recipientId) {
                if (intval($recipientId) === intval($userInfo['data']['uid'])) {
                    continue;
                }
                $records[] = [
                    'work_id' => $reportId,
                    'to_uid' => intval($recipientId),
                    'from_uid' => intval($userInfo['data']['uid']),
                    'send_time' => $sendTime,
                ];
            }
            if (!empty($records)) {
                Db::name('WorkRecord')->strict(false)->field(true)->insertAll($records);
            }
            Db::name('Work')->where('id', $reportId)->update(['send_time' => $sendTime]);
            event('SendMessage', [
                'from_uid' => intval($userInfo['data']['uid']),
                'to_uids' => implode(',', $recipientIds),
                'template_id' => 'work',
                'content' => [
                    'create_time' => date('Y-m-d H:i:s', $sendTime),
                    'action_id' => $reportId,
                ],
            ]);
        }

        return to_assign(0, $send === 1 ? '发送成功' : '创建成功', [
            'reportId' => (string)$reportId,
        ]);
    }

    /**
     * 获取待处理审批
     *
     * @return \think\Response
     */
    public function get_pending_approvals()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $categories = $this->normalizeApprovalCategories($param['categories'] ?? '');
        $pendingHoursGte = max(1, intval($param['pending_hours_gte'] ?? 48));
        $riskPolicy = strtoupper(trim(strval($param['risk_policy'] ?? 'HIGH_AMOUNT_TO_LEADER')));
        $uid = intval($userInfo['data']['uid']);

        $flowCates = Db::name('FlowCate')
            ->where('status', 1)
            ->field('title,check_table')
            ->select()
            ->toArray();

        $items = [];
        $seenTables = [];
        foreach ($flowCates as $flowCate) {
            $table = $flowCate['check_table'];
            if ($table === '' || isset($seenTables[$table]) || !$this->tableExists($table)) {
                continue;
            }
            $seenTables[$table] = true;

            $categoryCode = $this->mapApprovalCategory($table);
            if (!empty($categories) && !in_array($categoryCode, $categories, true)) {
                continue;
            }

            $fieldList = ['id', 'admin_id', 'did', 'create_time', 'check_status', 'check_uids', 'check_flow_id'];
            if ($this->tableHasColumn($table, 'check_copy_uids')) {
                $fieldList[] = 'check_copy_uids';
            }
            if ($this->tableHasColumn($table, 'cost')) {
                $fieldList[] = 'cost';
            }

            $records = Db::name($table)
                ->where('delete_time', 0)
                ->where('check_status', 1)
                ->whereRaw("FIND_IN_SET('{$uid}', check_uids)")
                ->field(implode(',', $fieldList))
                ->select()
                ->toArray();

            foreach ($records as $record) {
                $pendingHours = max(1, intval(floor((time() - intval($record['create_time'])) / 3600)));
                if ($pendingHours < $pendingHoursGte) {
                    continue;
                }

                $amount = floatval($record['cost'] ?? 0);
                $canEscalate = $riskPolicy === 'HIGH_AMOUNT_TO_LEADER'
                    && $categoryCode === 'expense'
                    && $amount >= 1000;
                $canRemind = !$canEscalate;
                $approvalId = $table . ':' . $record['id'];

                $items[] = [
                    'approval_id' => $approvalId,
                    'title' => $flowCate['title'] . '#' . $record['id'],
                    'category_code' => $categoryCode,
                    'flow_name' => Db::name('Flow')->where('id', intval($record['check_flow_id']))->value('title') ?? '',
                    'applicant' => Db::name('Admin')->where('id', intval($record['admin_id']))->value('name') ?? '',
                    'current_approver' => $this->implodeAdminNames($record['check_uids'] ?? ''),
                    'pending_hours' => $pendingHours,
                    'sla_hours' => $pendingHoursGte,
                    'amount' => $amount,
                    'priority' => $canEscalate ? 'HIGH' : 'NORMAL',
                    'node_name' => '当前待审批',
                    'can_remind' => $canRemind,
                    'can_escalate' => $canEscalate,
                ];
            }
        }

        usort($items, function ($a, $b) {
            if ($a['can_escalate'] === $b['can_escalate']) {
                return $b['pending_hours'] <=> $a['pending_hours'];
            }
            return $a['can_escalate'] ? -1 : 1;
        });

        $reminderApprovalIds = [];
        $escalationApprovalIds = [];
        foreach ($items as $item) {
            if (!empty($item['can_escalate'])) {
                $escalationApprovalIds[] = $item['approval_id'];
            } elseif (!empty($item['can_remind'])) {
                $reminderApprovalIds[] = $item['approval_id'];
            }
        }

        return to_assign(0, '成功', [
            'summary' => [
                'total' => count($items),
                'reminderCount' => count($reminderApprovalIds),
                'escalationCount' => count($escalationApprovalIds),
            ],
            'items' => $items,
            'reminderApprovalIds' => implode(',', $reminderApprovalIds),
            'escalationApprovalIds' => implode(',', $escalationApprovalIds),
            'hasReminders' => !empty($reminderApprovalIds),
            'hasEscalations' => !empty($escalationApprovalIds),
        ]);
    }

    /**
     * 催办审批
     *
     * @return \think\Response
     */
    public function remind_approval()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $approvalIds = $this->normalizeStringList($param['approval_ids'] ?? '');
        if (empty($approvalIds)) {
            return to_assign(1, '审批单ID不能为空');
        }

        $message = trim(strval($param['message'] ?? '请优先处理已积压审批'));
        $reminderCount = 0;
        foreach ($approvalIds as $approvalId) {
            $approval = $this->loadApprovalRecord($approvalId);
            if (empty($approval) || empty($approval['check_uids'])) {
                continue;
            }
            $title = '审批催办提醒';
            $content = $message . '（单据：' . $approvalId . '）';
            $this->insertDirectMessages(
                $this->normalizeIdList($approval['check_uids']),
                intval($approval['id']),
                $title,
                $content
            );
            $reminderCount++;
        }

        return to_assign(0, '催办完成', [
            'reminderCount' => $reminderCount,
        ]);
    }

    /**
     * 升级审批
     *
     * @return \think\Response
     */
    public function escalate_approval()
    {
        $userInfo = $this->verifyToken();
        if ($userInfo['code'] !== 0) {
            return to_assign($userInfo['code'], $userInfo['msg']);
        }

        $param = get_params();
        $approvalIds = $this->normalizeStringList($param['approval_ids'] ?? '');
        $targetUid = intval($param['target_uid'] ?? 0);
        $reason = trim(strval($param['reason'] ?? '审批已超过治理阈值，需要直属领导介入'));

        if (empty($approvalIds)) {
            return to_assign(1, '审批单ID不能为空');
        }
        if ($targetUid <= 0) {
            return to_assign(1, '升级目标不能为空');
        }

        $targetUser = Db::name('Admin')
            ->where('id', $targetUid)
            ->where('status', 1)
            ->field('id,name')
            ->find();
        if (empty($targetUser)) {
            return to_assign(1, '升级目标不存在或已禁用');
        }

        $escalationCount = 0;
        foreach ($approvalIds as $approvalId) {
            $approval = $this->loadApprovalRecord($approvalId);
            if (empty($approval)) {
                continue;
            }

            $updateData = [
                'id' => intval($approval['id']),
                'check_uids' => (string)$targetUid,
            ];
            if (array_key_exists('check_copy_uids', $approval)) {
                $copyIds = $this->normalizeIdList($approval['check_copy_uids']);
                $copyIds = array_values(array_unique(array_merge($copyIds, $this->normalizeIdList($approval['check_uids']))));
                $updateData['check_copy_uids'] = implode(',', $copyIds);
            }
            Db::name($approval['table_name'])->strict(false)->field(true)->update($updateData);

            $title = '审批升级提醒';
            $content = $reason . '（单据：' . $approvalId . '）';
            $this->insertDirectMessages([$targetUid], intval($approval['id']), $title, $content);
            $escalationCount++;
        }

        return to_assign(0, '升级完成', [
            'escalationCount' => $escalationCount,
        ]);
    }

    /**
     * 规范化字符串列表
     *
     * @param mixed $value
     * @return array
     */
    private function normalizeStringList($value): array
    {
        if (is_array($value)) {
            $values = $value;
        } else {
            $values = preg_split('/[,，、|\\s]+/u', trim(strval($value)));
        }

        $values = array_map(static function ($item) {
            return trim(strval($item));
        }, $values);

        return array_values(array_filter(array_unique($values), static function ($item) {
            return $item !== '';
        }));
    }

    /**
     * 规范化人员ID列表
     *
     * @param mixed $value
     * @return array
     */
    private function normalizeIdList($value): array
    {
        $values = $this->normalizeStringList($value);
        $values = array_map(static function ($item) {
            return preg_replace('/[^0-9]/', '', $item);
        }, $values);
        $values = array_values(array_filter(array_unique($values), static function ($item) {
            return $item !== '';
        }));
        return $values;
    }

    /**
     * 规范化会议需求
     *
     * @param mixed $value
     * @return array
     */
    private function normalizeMeetingRequirements($value): array
    {
        $values = $this->normalizeStringList($value);
        if (empty($values)) {
            return [];
        }

        $requirements = Db::name('BasicAdm')
            ->where(['status' => 1, 'types' => 2])
            ->field('id,title')
            ->select()
            ->toArray();
        $titleMapping = [];
        foreach ($requirements as $requirement) {
            $titleMapping[trim($requirement['title'])] = (string)$requirement['id'];
        }

        $result = [];
        foreach ($values as $valueItem) {
            if (preg_match('/^\d+$/', $valueItem)) {
                $result[] = $valueItem;
                continue;
            }
            if (isset($titleMapping[$valueItem])) {
                $result[] = $titleMapping[$valueItem];
            }
        }

        return array_values(array_filter(array_unique($result)));
    }

    /**
     * 规范化日期时间
     *
     * @param mixed $value
     * @param bool $endOfDay
     * @return int
     */
    private function normalizeDateTimeValue($value, bool $endOfDay): int
    {
        $value = trim(strval($value));
        if ($value === '') {
            return 0;
        }
        if (preg_match('/^\d{4}-\d{2}-\d{2}$/', $value)) {
            $value .= $endOfDay ? ' 23:59:59' : ' 00:00:00';
        }
        return strtotime($value) ?: 0;
    }

    /**
     * 规范化日期
     *
     * @param mixed $value
     * @param bool $endOfDay
     * @return int
     */
    private function normalizeDateValue($value, bool $endOfDay): int
    {
        $timestamp = $this->normalizeDateTimeValue($value, $endOfDay);
        if ($timestamp <= 0) {
            return 0;
        }
        return strtotime(date('Y-m-d', $timestamp) . ($endOfDay ? ' 23:59:59' : ' 00:00:00'));
    }

    /**
     * 计算请假时长
     *
     * @param int $startTimestamp
     * @param int $endTimestamp
     * @return string
     */
    private function calculateLeaveDuration(int $startTimestamp, int $endTimestamp): string
    {
        if ($endTimestamp < $startTimestamp) {
            return '0';
        }
        $seconds = ($endTimestamp - $startTimestamp) + 1;
        $days = round($seconds / 86400, 2);
        if ($days <= 0) {
            $days = 1;
        }
        return rtrim(rtrim(number_format($days, 2, '.', ''), '0'), '.');
    }

    /**
     * 规范化周报类型
     *
     * @param mixed $value
     * @return int
     */
    private function normalizeWorkType($value): int
    {
        $value = trim(strval($value));
        $mapping = [
            '1' => 1,
            '日报' => 1,
            '2' => 2,
            '周报' => 2,
            '3' => 3,
            '月报' => 3,
        ];
        return $mapping[$value] ?? 0;
    }

    /**
     * 规范化审批分类
     *
     * @param mixed $value
     * @return array
     */
    private function normalizeApprovalCategories($value): array
    {
        $mapping = [
            'leave' => 'leave',
            '请假' => 'leave',
            'leaves' => 'leave',
            'expense' => 'expense',
            '报销' => 'expense',
            '费用' => 'expense',
            'invoice' => 'expense',
            'ticket' => 'expense',
            'meeting' => 'meeting',
            '会议' => 'meeting',
        ];

        $result = [];
        foreach ($this->normalizeStringList($value) as $item) {
            if (isset($mapping[$item])) {
                $result[] = $mapping[$item];
            }
        }
        return array_values(array_unique($result));
    }

    /**
     * 映射审批分类编码
     *
     * @param string $table
     * @return string
     */
    private function mapApprovalCategory(string $table): string
    {
        $table = strtolower($table);
        if ($table === 'leaves') {
            return 'leave';
        }
        if (in_array($table, ['expense', 'invoice', 'ticket'], true)) {
            return 'expense';
        }
        if ($table === 'meeting_order') {
            return 'meeting';
        }
        return 'other';
    }

    /**
     * 读取审批记录
     *
     * @param string $approvalId
     * @return array|null
     */
    private function loadApprovalRecord(string $approvalId): ?array
    {
        $parts = explode(':', $approvalId, 2);
        if (count($parts) !== 2) {
            return null;
        }

        $table = trim($parts[0]);
        $id = intval($parts[1]);
        if ($table === '' || $id <= 0 || !$this->tableExists($table)) {
            return null;
        }

        $fieldList = ['id', 'admin_id', 'did', 'create_time', 'check_status', 'check_uids', 'check_flow_id'];
        if ($this->tableHasColumn($table, 'check_copy_uids')) {
            $fieldList[] = 'check_copy_uids';
        }

        $record = Db::name($table)
            ->where('id', $id)
            ->where('delete_time', 0)
            ->field(implode(',', $fieldList))
            ->find();
        if (empty($record)) {
            return null;
        }
        $record['table_name'] = $table;
        return $record;
    }

    /**
     * 查询用户直属领导候选
     *
     * @param int $uid
     * @return array
     */
    private function getLeaderCandidatesByUid(int $uid): array
    {
        $user = Db::name('Admin')
            ->where('id', $uid)
            ->field('id,did,name')
            ->find();
        if (empty($user) || empty($user['did'])) {
            return [];
        }

        $department = Db::name('Department')
            ->where('id', intval($user['did']))
            ->field('id,title,leader_ids')
            ->find();
        if (empty($department) || empty($department['leader_ids'])) {
            return [];
        }

        $leaderIds = $this->normalizeIdList($department['leader_ids']);
        if (empty($leaderIds)) {
            return [];
        }

        $leaders = Db::name('Admin')
            ->whereIn('id', $leaderIds)
            ->where('status', 1)
            ->field('id,username,name,mobile,did')
            ->select()
            ->toArray();
        if (empty($leaders)) {
            return [];
        }

        $result = [];
        foreach ($leaderIds as $leaderId) {
            foreach ($leaders as $leader) {
                if (intval($leader['id']) !== intval($leaderId)) {
                    continue;
                }
                $result[] = [
                    'id' => (string)$leader['id'],
                    'username' => $leader['username'],
                    'name' => $leader['name'],
                    'mobile' => $leader['mobile'] ?? '',
                    'department' => Db::name('Department')->where('id', intval($leader['did']))->value('title') ?? '',
                ];
                break;
            }
        }
        return $result;
    }

    /**
     * 直接写入站内消息
     *
     * @param array $toUids
     * @param int $actionId
     * @param string $title
     * @param string $content
     * @return int
     */
    private function insertDirectMessages(array $toUids, int $actionId, string $title, string $content): int
    {
        $insertRows = [];
        foreach ($this->normalizeIdList($toUids) as $toUid) {
            $insertRows[] = [
                'to_uid' => intval($toUid),
                'action_id' => $actionId,
                'title' => $title,
                'content' => $content,
                'template' => 0,
                'create_time' => time(),
            ];
        }
        if (empty($insertRows)) {
            return 0;
        }
        Db::name('Msg')->strict(false)->field(true)->insertAll($insertRows);
        return intval(Db::name('Msg')->order('id desc')->value('id') ?? 0);
    }

    /**
     * 拼接人员姓名
     *
     * @param mixed $uidList
     * @return string
     */
    private function implodeAdminNames($uidList): string
    {
        $uids = $this->normalizeIdList($uidList);
        if (empty($uids)) {
            return '';
        }
        $names = Db::name('Admin')->where('id', 'in', implode(',', $uids))->column('name');
        return implode(',', array_filter($names));
    }

    /**
     * 判断数据表是否存在
     *
     * @param string $table
     * @return bool
     */
    private function tableExists(string $table): bool
    {
        $tableName = $this->getFullTableName($table);
        $result = Db::query("SHOW TABLES LIKE '{$tableName}'");
        return !empty($result);
    }

    /**
     * 判断数据表字段是否存在
     *
     * @param string $table
     * @param string $column
     * @return bool
     */
    private function tableHasColumn(string $table, string $column): bool
    {
        $tableName = $this->getFullTableName($table);
        $result = Db::query("SHOW COLUMNS FROM {$tableName} LIKE '{$column}'");
        return !empty($result);
    }

    /**
     * 获取带前缀的数据表名
     *
     * @param string $table
     * @return string
     */
    private function getFullTableName(string $table): string
    {
        $prefix = \think\facade\Config::get('database.connections.mysql.prefix');
        return $prefix . $table;
    }

"@

if ($integrationContent -notmatch 'public function book_meeting\(\)') {
    $integrationContent = $integrationContent.Replace("    /**`r`n     * 测试接口", $actionBlock + "    /**`r`n     * 测试接口")
}

Set-Content -Path $integrationPath -Value $integrationContent -Encoding UTF8

$checkContent = Get-Content $checkPath -Raw
if ($checkContent -notmatch "leave_order'\s*=>\s*'leaves") {
    $old = "`$flow_cate = Db::name('FlowCate')->where(['name' => `$param['check_name']])->find();"
    $new = @"
        \$checkName = \$param['check_name'];
        \$checkNameMapping = [
            'leave_order' => 'leaves',
        ];
        if (isset(\$checkNameMapping[\$checkName])) {
            \$checkName = \$checkNameMapping[\$checkName];
        }
        \$flow_cate = Db::name('FlowCate')->where(['name' => \$checkName])->find();
"@
    $checkContent = $checkContent.Replace($old, $new)
    Set-Content -Path $checkPath -Value $checkContent -Encoding UTF8
}
