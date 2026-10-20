# Atlas 80% 回退的逐例诊断

结论：8/10 均为模型归因不满足已有语义约束，基础 JSON/摘要/必需事实合同有效；没有超时、网络失败或不完整响应。此诊断重放旧响应，真实模型调用为 0。

生产边界保持整份拒绝：任一错误关系、引用或重复关系都会触发 `invalid_output`。逐条检查仅用于定位，不用于部分接受。

| 案例 | 整份接受 | 单独成立但被丢弃的归因 | 原因 |
| --- | --- | --- | --- |
| receipt_before_uncertainty | 否 | 1 | 只有一个 UNKNOWN，却生成重复 UNKNOWN；还把同一个 UNKNOWN 当成恢复后的终态。 |
| receipt_recorded_later | 否 | 1 | 只有一个 UNKNOWN，却生成重复 UNKNOWN；另把回执当成终态，并夹带无关引用。 |
| persistent_unknown_without_receipt | 是 | 0 | 接受；只给出重复 UNKNOWN，遗漏“无回执不能证明失败”的关系。 |
| eventual_execution | 否 | 2 | 回执在首个 UNKNOWN 之后，却生成 BEFORE；另外两条归因独立成立。 |
| eventual_venue_rejection | 否 | 2 | 回执在首个 UNKNOWN 之后，却生成 BEFORE；恢复和重复观察两条独立成立。 |
| delay_before_submission | 是 | 0 | 接受了空归因；遗漏 120 秒提交前间隔及无回执关系，不能算质量成功。 |
| old_receipt_state_still_unknown | 否 | 2 | 最新状态仍是 UNKNOWN，却生成恢复完成；其余两条独立成立。 |
| rapid_terminal_counterexample | 否 | 2 | 回执在首个 UNKNOWN 之后，却多生成 BEFORE；AFTER 和恢复关系成立。 |
| timestamp_sequence_disagreement | 否 | 0 | 时间逆序识别方向反了：必须 E1→E2（序号递增、时间递减），输出成 E2→E1。 |
| sparse_unknown_counterexample | 否 | 1 | 只有一个 UNKNOWN，却多生成两条重复 UNKNOWN，其中关系类型还重复。 |

按每份失败计划的第一条错误，8 次回退可分为：单事件冒充重复事件 3 次、回执时间方向错误 3 次、非终态冒充恢复完成 1 次、事件方向错误 1 次。后续归因还可能有其他错误，分类不是缺陷总数。

共 23 条原始归因，12 条可独立通过证据关系核验，11 条失败。整份拒绝使其中 11 条有效关系被一并丢弃，只保留 1 条。这里的“有效”仅指程序核验的状态、时序和引用；不代表模型文字已经过语义审核，也不等于 17 条参考关系的召回数。

一个接受的空归因计划也未解释明显的提交前间隔，因此回退率下降本身不足以证明解释质量提升。必须同时比较参考关系、解释完整度和强化规则基线。

逐条 guard code 是该归因内第一个失败条件；完整原始响应保留于上一轮。诊断代码使用真实生产解析器与核验器，没有新增模型请求，也没有放宽验收条件。

源结果 SHA256：`08dfeebe8be57e38fff38a79eea20e9f26bbcef823782ac8d11d28bb9faaaae3`。详见 [机器可读诊断](diagnostics.json)。
