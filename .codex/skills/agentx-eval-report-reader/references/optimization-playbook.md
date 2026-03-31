# Optimization Playbook

这个 playbook 用来把 findings 稳定映射成实现建议。

## 1. `prompt/schema alignment`

适用问题：

1. 节点结构输出不稳定
2. summary / gaps / questions 质量低
3. verify decision 与证据冲突

建议动作：

1. 调整 system prompt
2. 强化 schema 示例
3. 补 few-shot 正反例
4. 增加 fail-fast 提示文案

## 2. `catalog alignment`

适用问题：

1. 非法 `taskTemplateId`
2. `capabilityPackId` 不匹配
3. write scope 超模板边界

建议动作：

1. 强化 architect catalog 白名单提示
2. 补 catalog few-shot
3. 补 materializer 前置校验
4. 收敛 task template 描述

## 3. `retrieval/context quality`

适用问题：

1. expected facts 丢失
2. snippet 命中率低
3. verify 缺 changed-files 周边上下文

建议动作：

1. 调整 fact extraction
2. 调整 retrieval query 生成
3. 调整 chunk strategy
4. 补 scenario 黄金集

## 4. `tool protocol alignment`

适用问题：

1. 绝对路径
2. 工具名/操作名不合法
3. 重复或浪费调用

建议动作：

1. 强化 tool prompt 约束
2. 强化 normalizer / validator 错误提示
3. 增加协议回归场景
4. 调整幂等复用规则说明

## 5. `runtime robustness`

适用问题：

1. retry / cleanup 证据不足
2. supervisor 越权
3. state machine 被恢复逻辑破坏

建议动作：

1. 补 task run event 证据
2. 补更清晰的 fail-fast 异常上下文
3. 收紧 supervisor 写入边界
4. 增加 recovery regression 场景

## 6. `test fixture / eval dataset gap`

适用问题：

1. 报告无法定位问题
2. 黄金集太弱
3. regression 无法复现

建议动作：

1. 增加 scenario 预期事实
2. 增加 snippet 黄金集
3. 补更真实的 failure fixture
4. 保持 artifactRefs 可追溯
