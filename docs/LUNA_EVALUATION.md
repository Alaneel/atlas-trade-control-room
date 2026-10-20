# Atlas：80% 回退诊断与 GPT-5.6 Luna 同案例比较

2026-10-21（Asia/Singapore）。Luna 的归因合同通过率和参考关系覆盖明显改善，生成成本更低；仍没有证据证明它稳定优于强化规则。默认继续使用强化规则，AI 保留为只读实验入口。

## 先定位原来的 80%

对上一轮全部原始响应进行零费用回放：10 次生成均为完整响应，基础 JSON、快照绑定及必需事实合同有效。8 次回退均来自不成立的归因。按每份计划第一条失败归因分类：

| 原因 | 失败计划数 |
| --- | ---: |
| 把单个 UNKNOWN 当成重复 UNKNOWN | 3 |
| 回执实际晚于首次 UNKNOWN，却写成 BEFORE | 3 |
| 仍为 UNKNOWN，却推断恢复到终态 | 1 |
| 时间逆序案例的引用方向反了 | 1 |

后续归因还存在回执冒充交易终态、重复关系和引用问题。生产端保持“任意一条错误就整份回退”：23 条原始归因中有 12 条可独立通过关系核验，其中 11 条随错误计划一起被丢弃。逐条核验只用于诊断，没有放宽生产接受条件。另有一份空归因计划通过合同，却没有解释 120 秒提交前间隔，说明低回退率本身也不等于解释有用。

[逐例诊断](examples/ai-explanations/cross-event-v3-diagnosis/report-zh.md) · [全部 guard 结果](examples/ai-explanations/cross-event-v3-diagnosis/diagnostics.json)

## Luna 的实测结果

保持同一份冻结数据：10 个异常案例、2 个弃答对照、17 条预先声明的参考关系；不重跑旧模型。生成提示仍为 v3，完整性、引用、时序核验和整份回退边界保持原样。生成模型使用 `gpt-5.6-luna`，standard/low；评审固定为上一轮的 `gpt-4.1-mini-2025-04-14`，继续匿名三方比较及反向位置复评。

两轮都使用 8 秒整体期限、1,400 输出 token 上限。Luna 的输出上限包含推理 token，使用显式缓存模式且不设断点，避免为变化的单轮上下文付缓存写入费。它是“同一服务约束下的已记录模型配置比较”，不是排除所有配置差异的纯模型因果实验。

| 指标 | 原 GPT-4.1 Mini v3 | GPT-5.6 Luna |
| --- | ---: | ---: |
| 真实生成 / 真实评审请求 | 10 / 20 | 10 / 20 |
| 回退 | 8/10（80%） | 2/10（20%） |
| 接受且含归因的计划 | 1/10 | 8/10 |
| 通过核验的归因对象 | 1 | 16 |
| 参考关系覆盖 | 1/17 | 14/17（82.4%） |
| 生成 P50 / P95 | 3.526 / 4.207 秒 | 4.388 / 6.289 秒 |
| 生成费用估计 | $0.0123564 | $0.0082502 |
| 含全部评审费用估计 | $0.0427060 | $0.0396046 |
| 无模型调用的弃答对照 | 2/2 | 2/2 |

Luna 生成费用降低约 33.2%；延迟上升约 24.4%（P50）和 49.5%（P95）。这些是 10 例顺序执行的最近秩百分位，没有负载或统计显著性推断。费用按返回的模型、处理档位与 token 用量估算，不是账单。

强化规则仍覆盖 **17/17** 参考关系，无模型费用。本轮纯归因枚举的本地 P50/P95 为 3/23 ms；它不包含 HTTP 或数据库读取，不能当作生产端到端延迟。

Luna 剩余两个回退案例：

- `eventual_venue_rejection`：三条归因本身都成立，但漏掉必需事实 `evidence.receipt`，整份回退。
- `sparse_unknown_counterexample`：快照哈希少抄一个字符，绑定失败；同时把 1 秒的 RESERVED→SUBMITTED 间隔错误归类为至少 60 秒的 DISPATCH_DELAY。有效的无回执关系一并丢弃。

所有 10 次生成都完整返回，未出现超时或截断，也没有隐藏重试。两个失败案例保留在分母。详见 [Luna 诊断](examples/ai-explanations/cross-event-luna-round/diagnostics.json)。

## 解释质量能得出什么结论

Luna 在本组案例上更能产生证据关系成立的解释：正确连接了重复 UNKNOWN、后续终态、提交前间隔、时间戳逆序等。程序核验的关系召回改善不依赖评审模型的主观分数，但也不证明模型自由文本的语义正确或实际用户价值。

本轮固定模型盲评的目标覆盖为原规则 46/60、强化规则 57/60、Luna 57/60。60 是 10 例×3 个目标×2 次展示，不是 60 个独立案例。两次展示方向一致时才计稳定胜负：

| Luna 对照 | 稳定胜 | 稳定平 | 稳定负 | 方向敏感或两次不一致 |
| --- | ---: | ---: | ---: | ---: |
| 原规则 | 1 | 1 | 0 | 8 |
| 强化规则 | 0 | 0 | 2 | 8 |

20/20 份评审 JSON 均有效，但 8/10 案例的偏好随展示方向改变。因此不能将单向胜出计为优势，也不能把目标覆盖 95% 当作独立准确率。同样的规则内容在旧轮得到 52/60、60/60，本轮却得到 46/60、57/60；[跨轮内容核对](examples/ai-explanations/cross-event-luna-round/baseline-identity.json)确认评审可见的规则文本、证据、归因和引用一致。这也直接显示模型评分漂移。固定评审减少更换裁判的混杂，但仍有同提供方偏好、相关错误、旧模型自评倾向以及第三选项影响。

评审未标记不支持断言、引用错误或不当操作建议；作者助手在事后复核全部 16 条接受归因时，仍发现四处需谨慎解释的措辞。例如 `showing an acknowledgement/commit gap` 将记录时序写成了较确定的机制解释；`receipt visibility` 和 `local evidence became available later` 超出了本地创建时间能证明的范围；另有 UNKNOWN 被称为 `unresolved terminal state`。独立的不确定性句子和权威当前状态能限制误解，但不能代替语义核实。[助手文字复核](examples/ai-explanations/cross-event-luna-round/assistant-text-audit.json)是定性检查，不是人工盲评或新的准确率评分；没有为此调用额外模型。

结论：**相对旧模型，结构化归因正确性提高；相对强化规则，尚未建立稳定解释质量增益。** 当前适合展示严谨的模型比较、证据边界和明确的负面结果，不能宣称 AI 已优于确定性方案。

## 成本、配置与复现

本轮在第一次付费请求前记录了[协议](LUNA_EVALUATION_PROTOCOL.md)，源码版本为 `32be908bed4470a140afc056c2b393e491e4fb15`，启动时工作树干净。案例 SHA256 为 `05190bfdd3e6d9519af9b5cb14a6f9d7208a5e4c1353b68f7883d94bb58d10bf`。运行标识 `20261020T162703Z-fcbda53a` 的时间是 UTC，对应本地 10 月 21 日。

请求上限 30，运行预算 $0.10，实际发起 30 次并完整取得全部用量；没有缓存命中或缓存写入。Luna 生成输入 17,677 token，输出 3,929 token（已含 1,351 推理 token）；固定评审输入 55,254、输出 5,783 token。预算逻辑在每次发出请求前按保守 token 上限预留，收到用量后结算，未知用量保留预留并停止后续发出请求。模型可用性只读查询没有生成 token 费用。该预算不是账户级账单限制，不含税及账户定价差异。

```sh
python3 scripts/run-cross-event-eval.py --model gpt-5.6-luna \
  --reviewer-model gpt-4.1-mini-2025-04-14 --max-usd 0.10 --check-config
```

`--check-config` 不调用模型；移除它才会付费。模型覆盖只作用于该次评测，现有 `.env.local` 与 key 保持不变。历史结果与规则默认值保留。当前仓库已完成本轮测量，无需为查看报告或 demo 再运行。

[原始结果](examples/ai-explanations/cross-event-luna-round/results.json) · [汇总](examples/ai-explanations/cross-event-luna-round/summary.json) · [运行清单及源码哈希](examples/ai-explanations/cross-event-luna-round/manifest.json) · [机器可读跨轮比较](examples/ai-explanations/cross-event-luna-round/comparison.json)

官方资料：[Luna 模型及能力](https://developers.openai.com/api/docs/models/gpt-5.6-luna)、[计费](https://developers.openai.com/api/docs/pricing)、[推理配置](https://developers.openai.com/api/docs/guides/reasoning#reasoning-mode)、[缓存计费与配置](https://developers.openai.com/api/docs/guides/prompt-caching)。当前标准价每百万 token：Luna 输入 $0.20、缓存读取 $0.02、缓存写入 $0.25、输出 $1.20。缓存写入替代普通输入计费，推理 token 已包含在输出中，均不重复相加。

## 只读与验证

AI 仍只能获得经约束的证据快照；没有修改额度、批准、提交、重试、对账或执行交易的工具。API 保留 GET 解释、POST 拒绝，规则事实和当前状态始终由确定性代码渲染。完整本地回归通过，五种 HTTP 演示验证解释前后交易、额度、审计和回执逐文档哈希一致；旧两轮及 Luna 全部保留响应回放维持原接受／拒绝结果。默认测试与 CI 不加载私有 key，也不发出付费评测请求。

[验证记录](examples/ai-explanations/cross-event-luna-validation/full-verify-summary.json)包含完整 verify 结果，后续诊断与预算边界定向复核另存。全部案例为开发可见的合成数据；仍需未见案例、独立人工盲评与生产观测，才能判断泛化和实际价值。
