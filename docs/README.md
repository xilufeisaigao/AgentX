# AgentX Docs

本文档是仓库文档索引，目标是让你快速找到“规范真相源”和部署方式。

## 文档优先级

1. 表结构真相：`docs/schema/agentx_schema_v0.sql`
2. 语义真相：`docs/09-control-plane-api-contract.md`
3. 接口形状真相：`docs/openapi/agentx-control-plane.v0.yaml`

## 核心设计文档

1. `docs/01-project-value-and-user-flow.md`
2. `docs/02-concepts-and-roles.md`
3. `docs/03-project-design-module.md`
4. `docs/04-foreman-worker-module.md`
5. `docs/05-worker-execution-and-monitoring.md`
6. `docs/06-git-worktree-workflow.md`
7. `docs/07-definition-of-done.md`
8. `docs/08-context-management-module.md`
9. `docs/09-control-plane-api-contract.md`
10. `docs/10-class-structure-and-dependency-design.md`
11. `docs/11-requirement-doc-standard.md`

## 部署与运行

1. Docker 运行指南：`docs/deployment/docker-runtime.md`
2. 接口契约：`docs/openapi/agentx-control-plane.v0.yaml`
3. 数据库结构：`docs/schema/agentx_schema_v0.sql`
4. 当前工程快照：`docs/plans/status/2026-03-08-codex-project-snapshot.md`
5. 当前前端交互方案：`docs/plans/status/2026-03-08-codex-frontend-redesign.md`

## 文档整理说明

`docs/plans/**` 仅保留阶段性状态快照与仍在跟踪的事项；
过期的交接稿/临时计划会在收敛后删除，避免发布版本中出现失效执行说明。

其中推荐优先阅读：

1. `docs/plans/status/2026-03-08-codex-project-snapshot.md`
   - 当前真实可跑到哪一步
   - 已验证的端到端场景
   - 仍待继续扩大的风险面
2. `docs/plans/status/2026-03-08-codex-frontend-redesign.md`
   - 当前前端信息架构
   - 已实现页面分区
   - 下一步前后端并行接口面
