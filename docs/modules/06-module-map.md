# 模块文档导航

模块文档按源码真实包结构整理，不按旧设计稿的角色概念拆分。

## 读法建议

如果你是第一次重新接手这个项目，建议顺序：

1. [07-process.md](07-process.md)
   先看编排总线，再看单模块，否则容易只见树木不见森林。
2. [08-query.md](08-query.md)
   先分清“数据库字段”和“前端看到的聚合视图”。
3. [09-session.md](09-session.md)
4. [10-requirement.md](10-requirement.md)
5. [11-planning.md](11-planning.md)
6. [12-contextpack.md](12-contextpack.md)
7. [13-workforce.md](13-workforce.md)
8. [14-execution.md](14-execution.md)
9. [15-workspace.md](15-workspace.md)
10. [16-mergegate.md](16-mergegate.md)
11. [17-delivery.md](17-delivery.md)
12. [18-ticket.md](18-ticket.md)

## 每个模块文档都包含什么

每一页都会固定回答这些问题：

1. 这个模块负责什么。
2. 它的 API / 事件 / scheduler 入口是什么。
3. 它主要碰哪些表。
4. 真正值得先看的类和方法是什么。
5. 排查问题时先看什么命令、目录和接口。
6. 接下来可以怎么逐步工程化。

## 依赖方向提醒

当前仓库是模块化单体，遵守的主方向仍然是：

- `api -> application -> domain <- infrastructure`

但从当前现实看，真正决定系统能不能闭环的并不是单模块内部，而是：

1. `process` 对跨模块编排的控制
2. `query` 对状态聚合的解释方式
3. `execution + workspace + mergegate` 对 git/runtime 生命周期的串联

所以读模块时不要只看目录边界，也要同时看它在全链路里的位置。
