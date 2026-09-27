# cc-water-rights

管理季节取水许可的分配额度、用水申报台账，并支持通过冲正修复错误申报。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2 数据库 + Spring Data JPA

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

### 1. 季节许可

- 创建许可时记录**唯一许可号**、权利人、取水点、起止日期和核准水量。
- 水量使用 `BigDecimal(19,3)` 固定 3 位精度（四舍五入归一化），**必须大于零**。
- 截止日期不得早于起始日期；许可号由数据库唯一约束 `uk_permit_no` 保证唯一，重复创建返回 `409 PERMIT_NO_DUPLICATED`。

### 2. 用水申报

- 申报包含**外部事件号**、发生日期、水量（必须大于零）。
- 发生日期必须落在许可有效期 `[起始日, 截止日]` 内，否则 `422 EVENT_DATE_OUT_OF_RANGE`。
- **累计有效用水不得超过核准水量**；超出返回 `422 QUOTA_EXCEEDED`，该申报不写入、不占额度（恰好等于核准量允许）。
- 外部事件号全局唯一，是幂等键：
  - 相同内容重放 → 返回原事件，HTTP `200`，响应中 `replayed=true`，不重复扣额度；
  - 同一事件号但日期/水量/类型/所属许可/冲正目标任一不同 → `409 IDEMPOTENCY_CONTENT_CONFLICT`。

### 3. 冲正（修复错误申报）

- 错误申报**不可修改、不可删除**，只能新增一条冲正事件抵消它。
- 冲正必须**完整抵消**原申报：冲正水量必须与原申报完全一致（`422 REVERSAL_VOLUME_MISMATCH`）。
- 冲正后：原申报标记为已冲正，有效用水等额扣减、已冲正量等额增加、**可用额度恢复**。
- **冲正事件本身不可再次冲正**（`422 REVERSAL_NOT_REVERSIBLE`）。
- 同一冲正请求（相同外部事件号与内容）重放返回原结果；对已冲正申报再发**不同的**冲正请求返回 `409 DECLARATION_ALREADY_REVERSED`。
- 冲正属于事后纠错，其发生日期不要求落在许可有效期内。

### 4. 事务与并发一致性

- 申报/冲正的事件写入、原申报状态与许可余额更新在**同一事务**内完成（`@Transactional`）。
- 事务开始先按许可号加**悲观行锁**（`PESSIMISTIC_WRITE`），串行化同一许可上的所有额度变更：
  - 多笔申报并发争抢剩余额度时不会超额，落选申报得到 `QUOTA_EXCEEDED`；
  - 申报与冲正并发时，无论谁先执行，最终的剩余额度、累计有效用水、已冲正量与审计事件恒一致（等价于两种合法串行化之一）。
- 唯一约束 `uk_external_event_no` / `uk_permit_no` 为幂等与许可号唯一性兜底。

### 5. 台账查询

`GET /api/permits/{permitNo}` 返回核准量、有效用水、已冲正量、剩余额度，以及按创建顺序排列的**不可变事件列表**（申报/冲正，冲正事件带有 `originalEventNo` 指向原申报）。

恒等式：`剩余额度 = 核准水量 − 有效用水 ≥ 0`，`有效用水 = 申报合计 − 冲正合计`。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/permits` | 创建季节许可 |
| GET | `/api/permits/{permitNo}` | 查询许可台账 |
| POST | `/api/permits/{permitNo}/declarations` | 登记用水申报（支持幂等重放） |
| POST | `/api/permits/{permitNo}/reversals` | 创建冲正事件（支持幂等重放） |

示例：创建许可与申报

```json
POST /api/permits
{
  "permitNo": "P-2026-001",
  "owner": "张三",
  "intakePoint": "一号取水口",
  "startDate": "2026-04-01",
  "endDate": "2026-09-30",
  "authorizedVolume": "100.000"
}
```

```json
POST /api/permits/P-2026-001/declarations
{
  "externalEventNo": "EVT-1001",
  "occurrenceDate": "2026-05-01",
  "volume": "30.500"
}
```

```json
POST /api/permits/P-2026-001/reversals
{
  "externalEventNo": "REV-1001",
  "originalEventNo": "EVT-1001",
  "occurrenceDate": "2026-10-05",
  "volume": "30.500"
}
```

## 统一错误格式

所有错误返回统一 JSON：

```json
{
  "code": "QUOTA_EXCEEDED",
  "message": "累计有效用水将超过核准水量，剩余额度: 69.500",
  "fieldErrors": null
}
```

字段校验失败时 `fieldErrors` 为字段 → 错误信息的映射，`code=VALIDATION_ERROR`。

| HTTP | code | 触发场景 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` / `MALFORMED_REQUEST` | 参数校验失败 / JSON 不合法 |
| 404 | `PERMIT_NOT_FOUND` / `EVENT_NOT_FOUND` | 许可或原申报事件不存在（含不属于该许可） |
| 409 | `PERMIT_NO_DUPLICATED` / `IDEMPOTENCY_CONTENT_CONFLICT` / `DECLARATION_ALREADY_REVERSED` | 许可号重复 / 事件号内容冲突 / 申报已被其他冲正抵消 |
| 422 | `INVALID_DATE_RANGE` / `EVENT_DATE_OUT_OF_RANGE` / `QUOTA_EXCEEDED` / `REVERSAL_VOLUME_MISMATCH` / `REVERSAL_NOT_REVERSIBLE` | 业务规则拒绝 |

## 测试

- `PermitLedgerServiceTest`：许可创建、申报、额度控制、幂等重放/冲突、冲正抵消/重复冲正/冲正再冲正、台账恒等式等 25 个用例。
- `PermitLedgerConcurrencyTest`：8 线程并发争抢额度（恰好 3 笔成功、无超额）、20 轮申报与冲正并发交错（校验余额与审计事件一致）、4 个不同冲正并发只生效一次。
- `PermitControllerTest`：HTTP 状态码、统一错误 JSON、端到端申报→重放→冲突→冲正生命周期。
