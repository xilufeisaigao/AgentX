# workforce 模块

## 职责

`workforce` 管 worker 和 toolpack 能力面：

- 注册 toolpack
- 注册 worker
- 绑定 worker 与 toolpack
- 判断某组 toolpack 是否有可用 worker

它本身不直接执行任务，但 execution 和 process 都依赖它来决定“谁能干活”。

## 入站入口

- API:
  [WorkforceAutomationController](../../src/main/java/com/agentx/agentxbackend/process/api/WorkforceAutomationController.java)
  - `autoProvision`
  - `listWorkers`

另外还有启动时自动注入：

- [DefaultToolpackBootstrap](../../src/main/java/com/agentx/agentxbackend/workforce/infrastructure/external/DefaultToolpackBootstrap.java)
  - `bootstrap`

## 主要表

- `toolpacks`
- `workers`
- `worker_toolpacks`

## 关键代码入口

- 核心能力:
  [WorkerCapabilityService](../../src/main/java/com/agentx/agentxbackend/workforce/application/WorkerCapabilityService.java)
  - `registerToolpack`
  - `registerWorker`
  - `updateWorkerStatus`
  - `bindToolpacks`
  - `hasEligibleWorker`
  - `isWorkerEligible`
  - `listWorkersByStatus`
- 自动 provision:
  [WorkerAutoProvisionService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerAutoProvisionService.java)
  - `provisionForWaitingTasks`

## 在全链路里的位置

它决定了 planning 产出的任务是否真的有人可接。

当前最小闭环里，默认 worker profile 已经由 backend 启动自动补齐：

- `WRK-BOOT-JAVA-CORE`
- `WRK-BOOT-JAVA-DB`
- `WRK-BOOT-PYTHON-AUX`

## 想查什么就看哪里

- 为什么某个任务会被某个 worker 抢到
  - 看 `required_toolpacks_json`
  - 再看 [WorkerCapabilityService](../../src/main/java/com/agentx/agentxbackend/workforce/application/WorkerCapabilityService.java)
- 为什么系统自动出现了这些 worker
  - 看 [DefaultToolpackBootstrap](../../src/main/java/com/agentx/agentxbackend/workforce/infrastructure/external/DefaultToolpackBootstrap.java)
- 为什么 waiting task 没有被 provision 出 worker
  - 看 [WorkerAutoProvisionService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerAutoProvisionService.java)

## 调试入口

- API: `GET /api/v0/workforce/workers`
- API: `POST /api/v0/workforce/auto-provision`
- SQL: `select * from workers;`
- SQL: `select * from worker_toolpacks where worker_id = '<WORKER_ID>';`

## 工程优化思路

### 近期整理

- 补 toolpack 兼容规则说明，尤其是 `TP-JAVA-21` 满足 `TP-JAVA-17` 的现实规则。
- 统一 worker 状态和查询视图里的推导状态描述。

### 可维护性与可观测性

- 给 auto provision 补“为什么没有 provision”的解释输出。
- 把 worker 选择结果和 task 需求之间的匹配过程记录成可检索事件。

### 中长期演进

- 将 worker/toolpack 选择从静态匹配演进为能力评分模型。
- 把 worker profile、runtime env、隔离策略拆成更明确的工程实体。
