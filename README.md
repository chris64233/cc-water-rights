# cc-water-rights

管理取水许可、季节配额与用水申报。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 主要业务规则

1. **季节许可**：创建时记录唯一许可号、权利人、取水点、起止日期与核准水量。
   水量使用 `BigDecimal`（精度 19、3 位小数）且必须大于零；起始日期不得晚于截止日期；
   许可号全局唯一（数据库唯一约束兜底）。
2. **用水申报**：申报携带外部事件号、发生日期与水量，发生日期必须落在许可有效期内。
   累计有效用水（申报合计 − 冲正合计）不得超过核准水量，超额申报被拒绝。
   事件号保证幂等：相同事件号、相同内容的重放返回原结果；事件号相同但内容不同返回 `409 EVENT_CONFLICT`。
3. **冲正**：错误申报不可修改、不可删除，只能追加一条冲正事件完整抵消原申报（水量与原申报一致），
   并恢复可用额度。冲正事件本身不可再被冲正；一笔申报至多被冲正一次
   （`reverses_event_id` 唯一约束）。已冲正申报的相同冲正请求重放返回原结果，
   不同的冲正请求返回 `409 ALREADY_REVERSED`。
4. **事务与并发**：申报、冲正与许可余额（`used_volume`）更新在同一事务内完成。
   写路径先对许可行加悲观写锁（`SELECT ... FOR UPDATE`）再校验额度，
   多个申报并发争抢剩余额度不会超额；申报与冲正并发时，
   最终余额、累计有效用水与事件流水保持一致。加锁后会复查事件号，
   并发下的同事件号重放只会入账一次。
5. **台账查询**：返回核准量、有效用水、已冲正量、剩余额度及按写入顺序排列的不可变事件列表。
   所有错误响应为统一 JSON：`{"code", "message", "timestamp"}`。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/permits` | 创建季节许可（201） |
| POST | `/api/permits/{permitNo}/declarations` | 用水申报 |
| POST | `/api/permits/{permitNo}/reversals` | 冲正指定申报 |
| GET | `/api/permits/{permitNo}/ledger` | 许可台账 |

示例：

```bash
# 创建许可
curl -X POST localhost:8080/api/permits -H 'Content-Type: application/json' -d '{
  "permitNo": "P-2026-001", "holder": "灌区合作社", "intakePoint": "东风渠3号闸",
  "startDate": "2026-04-01", "endDate": "2026-09-30", "approvedVolume": 1000.000
}'

# 用水申报
curl -X POST localhost:8080/api/permits/P-2026-001/declarations -H 'Content-Type: application/json' -d '{
  "eventNo": "D-0001", "occurredDate": "2026-05-01", "volume": 120.500
}'

# 冲正（水量取自被冲正的申报，完整抵消）
curl -X POST localhost:8080/api/permits/P-2026-001/reversals -H 'Content-Type: application/json' -d '{
  "eventNo": "R-0001", "occurredDate": "2026-05-02", "declarationEventNo": "D-0001"
}'

# 台账
curl localhost:8080/api/permits/P-2026-001/ledger
```

## 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | 请求参数校验失败（如水量非正数、缺字段） |
| `MALFORMED_REQUEST` | 400 | 请求体无法解析 |
| `PERMIT_PERIOD_INVALID` | 422 | 许可起止日期无效 |
| `EVENT_OUT_OF_PERIOD` | 422 | 申报日期不在许可有效期内 |
| `PERMIT_NOT_FOUND` | 404 | 许可不存在 |
| `DECLARATION_NOT_FOUND` | 404 | 被冲正的申报不存在或不属于该许可 |
| `PERMIT_NO_DUPLICATE` | 409 | 许可号已存在 |
| `EVENT_CONFLICT` | 409 | 事件号已存在且内容不一致 |
| `QUOTA_EXCEEDED` | 409 | 累计有效用水将超过核准水量 |
| `ALREADY_REVERSED` | 409 | 该申报已被冲正 |
| `REVERSAL_NOT_ALLOWED` | 409 | 冲正事件不可再次冲正 |
