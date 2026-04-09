# Archived Layered Vector RAG Design

本文保留为历史设计归档，不再作为 AgentX 下一阶段 coding 主链的目标方向。

状态说明：

1. 本文曾经用于描述“把 lexical/symbol baseline 升级成 layered vector RAG”的旧方案。
2. 该方向已被新的 coding 目标方案替代。
3. 当前新的目标设计见 `docs/runtime/07-unix-exploration-coding-context-design.md`。
4. 当前代码真相仍然以 `docs/runtime/04-local-rag-and-code-indexing.md` 为准。

## 1. 为什么归档

归档原因不是“旧方案完全错误”，而是我们重新调整了 coding 主链的优先级判断：

1. 结构化事实层继续保留。
2. coding 阶段更需要 agent 主动探索仓库，而不是平台预先喂代码 snippets。
3. 相比继续把代码 RAG 做重，当前更重要的是 Unix 式探索工具、读写权限隔离和越权 blocker 升级链路。

## 2. 哪些部分仍然有参考价值

本文仍保留这些历史价值：

1. 它强调过结构化事实层不应被模糊召回替代。
2. 它保留了 base repo / workflow overlay 分层思路的历史背景。
3. 它可以作为 requirement / architect / verify 这类节点未来是否保留 retrieval 的参考材料。

## 3. 哪些部分不再作为 coding 目标

以下方向不再作为 coding 主链的下一步设计：

1. 继续围绕 code chunks / embeddings / vector store 强化 coding pack。
2. 把 repo 代码片段检索作为 coding 的默认主路径。
3. 让 coding agent 优先依赖平台预注入的代码检索结果。

## 4. 当前替代方向

新的目标方向固定为：

1. 保留结构化事实上下文层。
2. coding pack 默认不再注入 repo code retrieval。
3. coding agent 通过 Unix 类只读探索工具主动确认代码真相。
4. 读权限放宽，写权限收窄。
5. 超出写域时显式升级为 blocker，而不是自动扩权。

详细方案见：

- `docs/runtime/07-unix-exploration-coding-context-design.md`
