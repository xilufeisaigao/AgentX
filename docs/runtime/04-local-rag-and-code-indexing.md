# Local RAG And Code Indexing

本文只描述当前已经落地的本地 RAG 基础设施。当前版本不接向量库、不接 embeddings、不接外部网页或外部知识源。

状态说明：

1. 本文仍然是当前代码真相。
2. 但它不再代表下一阶段 coding 设计方向。
3. 新的目标方案见 `docs/runtime/07-unix-exploration-coding-context-design.md`。
4. `docs/runtime/06-layered-vector-rag-design.md` 仅保留为历史设计归档。

## 1. 目标

当前本地 RAG 的目标不是“语义效果最好”，而是先把检索基础设施和证据链跑通。

核心要求只有两个：

1. 结构化事实优先精确取数
2. 当前 workflow 新写出来的代码必须能被后续 task / verify 检索到

## 2. 两条检索通道

### Structured Fact Retrieval

这一条通道只做精确取数，不做向量化。

当前来源：

1. requirement / ticket
2. module / task / dependency
3. snapshot / run / run event
4. workspace
5. workflow / node run

说明：

1. `TASK_BLOCKING` ticket 的 task 关联现在走 `tickets.task_id` 精确查询。
2. requirement / ticket / run / workspace 这类结构化真相不会被 lexical recall 替代。

### Local Text / Code Retrieval

这一条通道负责 repo、docs、schema、日志、长文本说明的 chunk 检索。

当前策略：

1. chunking
2. lexical recall
3. path / file-name weighting
4. Java symbol match
5. workflow overlay 优先

## 3. 当前索引结构

当前索引不落数据库主表，统一落本地 index root：

- `agentx.platform.retrieval.index-root`

当前拆成两层：

### Base Repo Index

基于配置的 `repo-root + base commit` 建索引，覆盖：

1. repo 代码
2. docs
3. schema
4. 常规配置文件

### Workflow Overlay Index

基于当前 workflow 的 worktree / merge candidate 建增量索引，覆盖：

1. 本轮刚写出来的类
2. 本轮刚改的方法
3. 新测试
4. 配置和文档改动

查询顺序固定为：

1. 先查 overlay
2. 再查 base repo
3. 最后补 docs / schema / general repo chunks

## 4. 当前支持的文件类型

第一版重点覆盖这些文本类型：

1. `.java`
2. `.md`
3. `.sql`
4. `.yaml`
5. `.yml`
6. `.json`
7. `.properties`
8. `.xml`
9. `.kt`
10. `.gradle`
11. `.txt`

其中：

1. Java 额外建立 symbol index
2. 其他类型先走 lexical chunking

## 5. Java Symbol Index

当前 Java symbol index 至少提取：

1. package
2. class / interface / enum 名
3. method 名
4. imports
5. file path

这让 coding / verify 在复杂仓库里可以先跑：

1. 类名模糊查找
2. 方法名查找
3. 注解关键字查找
4. 测试名查找
5. 路径关键字查找

## 6. 与 Context Pack 的关系

`ContextCompilationCenter` 不直接扫描仓库，而是通过 retrieval 基础设施取回 `RetrievalBundle` 再组装 pack。

当前关系是：

1. requirement pack 默认不带 repo retrieval
2. architect pack 带轻量 repo / docs / schema 片段
3. coding pack 带 task 相关 repo retrieval，同时补入 tool catalog、allowed command catalog 和 write scope guardrails
4. verify pack 带 changed-files 周边 retrieval，并补 deterministic verify evidence 与最近运行证据摘要

## 7. 哪些对象后续适合向量化

本轮不做 embeddings，但已经把后续最适合向量化的对象边界留出来了：

1. repo code chunks
2. workflow overlay code chunks
3. requirement / ticket 长文本
4. task run / verify 日志与摘要
5. docs / schema chunks

这些对象后续可以替换 text/code retrieval 通道，但不会替代结构化真相表。

## 8. 当前结论

当前本地 RAG 已经不是空壳：

1. 有独立接口层
2. 有 base repo index
3. 有 workflow overlay index
4. 有 lexical + symbol baseline
5. 能把本轮新代码重新喂回 coding / verify 上下文

后续优化项主要只剩效果升级：

1. embeddings / vector store
2. 更好的排序与 rerank
3. 更细粒度 chunk 策略
4. 更强的上下文压缩

这些升级项已经不再是 coding 阶段的主方向；当前运行真相仍然以本文描述的 lexical / symbol baseline 为准，直到 runtime/tooling 真正切换到新的 Unix 探索方案。
