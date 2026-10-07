#!/usr/bin/env bash
#
# 研约通“演示就绪体检”——只读、可复跑。
#
# 用法（在仓库根目录）：
#   bash scripts/demo-readiness.sh
#
# 所有凭据只从环境变量读取：MYSQL_PWD、REDISCLI_AUTH、RABBITMQ_USER、RABBITMQ_PASS。
# 本脚本只执行 SELECT、Redis 只读命令和 RabbitMQ 管理 API GET；不会改写任何运行数据。
# 本检查应在演示开始前运行：登录、消息投递或死信重放会改变 token、队列或 DLQ 的观测值。
#
# ── 输出等级 ────────────────────────────────────────────────────────────
# [PASS] 探测成功且与已记录的基线值一致，不置退出码 1。
# [FAIL] 探测无法执行（连接/认证/自证/解析失败）或基线值偏离记录值，置退出码 1。
# [SKIP] 未提供凭据，未探测，不置退出码 1。
# [WARN] 探测成功，但结果落在已知边界（② 无可用核验预约 / ③ 死信表无活数据），不置退出码 1。
#
# ── 适用范围 ────────────────────────────────────────────────────────────
# 本脚本是基线态检查：它断言这台机器的演示环境与已记录的基线逐项一致。
# ①④ 中钉死的精确值是基线断言，而非永远不变的不变量；任何改数据的操作都会使其
# 失效，包括演示本身，例如学生下一次预约、管理员发布一个批次、取消预约或核验预约。
# 正确顺序是先跑体检、后演示。演示后再次运行时，①④ 出现 [FAIL] 是预期行为，表示
# 环境已偏离基线，不等于环境损坏；如需恢复绿灯，须重新记录基线或还原数据。
# 两种判据风格并存是有意的：①④ 钉基线，偏离即红；②③ 使用形态判据，因为它们
# 测的是会随时间漂移的已知边界（② 甚至带硬日期），请勿将两者“对齐”。
#
# ── 变量展开硬性约定 ────────────────────────────────────────────────────
# 变量后面紧跟非 ASCII 字符时必须写 ${VAR}。macOS 的 bash 3.2 在 UTF-8 下会把
# 未加花括号的变量之后的全角字符吸进变量名，进而触发 unbound variable。
#
# ── 手工回填（仅演示用；本脚本永不自动执行）──────────────────────────────
# 若要让“成功核验”链路在演示时有活数据，须由人工在演示前选定 batchId 并手动执行：
# 1. 先记原值：
#    SELECT id, start_time, end_time FROM tb_reservation_batch WHERE id = <batchId>;
# 2. 正操作（只改 start_time，不动 end_time）：
#    UPDATE tb_reservation_batch SET start_time = DATE_SUB(NOW(), INTERVAL 30 MINUTE) WHERE id = <batchId>;
# 3. 演示后逆操作；必须使用第 1 步记下的原值手动还原：
#    UPDATE tb_reservation_batch SET start_time = '<原值>' WHERE id = <batchId>;
# 这会绕过 publish 阶段 booking_end_time <= start_time 的不变式，仅供演示使用。
# 务必演示前手动跑正操作、演示后手动跑逆操作；本脚本永不自动执行上述写操作。

set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO" || { echo "无法进入仓库根目录：$REPO"; exit 1; }

MYSQL_BIN=$(command -v mysql)
[ -n "$MYSQL_BIN" ] || MYSQL_BIN=/opt/homebrew/bin/mysql
REDIS_BIN=$(command -v redis-cli)
[ -n "$REDIS_BIN" ] || REDIS_BIN=/opt/homebrew/bin/redis-cli
MYSQL_STATE=SKIP
MYSQL_AVAILABLE=0
REDIS_STATE=SKIP
RABBIT_STATE=SKIP
BACKEND_STATE=FAIL
FAIL=0

VERIFY_COUNT=''
DLQ_COUNT=''
RESERVATION_TOTAL=''
REQUEST_TOTAL=''
ADMIN_ONE_COUNT=''
ADMIN_TWO_COUNT=''
LOGIN_TOKEN_COUNT=''

hdr()  { printf '\n== %s ==\n' "$1"; }
ok()   { printf '  [PASS] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; FAIL=1; }
info() { printf '  [SKIP] %s\n' "$1"; }
warn() { printf '  [WARN] %s\n' "$1"; }
vlines() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | grep -c ''; fi; }

mysql_hint() {
  # 占位符必须用闸门 verify-acceptance.sh 白名单里的 token（your_password_here / change_me 等）：
  # 该行会被 D 段结构性正则命中，只有含白名单 token 才会被 PLACEHOLDER 过滤放行。
  printf "        修复后重跑：export MYSQL_PWD='your_password_here'  # 换成你的 MySQL 口令\n"
}

redis_hint() {
  printf "        修复后重跑：export REDISCLI_AUTH='<Redis 口令>'\n"
}

rabbit_hint() {
  # 占位符必须用闸门 verify-acceptance.sh 白名单里的 token（your_password_here / change_me 等）：
  # 该行会被 D 段结构性正则命中，只有含白名单 token 才会被 PLACEHOLDER 过滤放行。
  printf "        修复后重跑：export RABBITMQ_USER='<RabbitMQ 用户名>'\n"
  printf "                    export RABBITMQ_PASS='your_password_here'  # 换成你的 RabbitMQ 口令\n"
}

mysql_expect() {
  mysql_label=$1
  mysql_sql=$2
  mysql_expected=$3
  MYSQL_LAST=''
  if MYSQL_LAST=$("$MYSQL_BIN" --protocol=TCP -h 127.0.0.1 -P 3306 -u root -N -B hmdp -e "$mysql_sql"); then
    if [ "$MYSQL_LAST" = "$mysql_expected" ]; then
      ok "$mysql_label = $MYSQL_LAST"
    else
      bad "${mysql_label} = ${MYSQL_LAST}（期望 ${mysql_expected}）"
      MYSQL_STATE=FAIL
    fi
  else
    bad "$mysql_label 查询失败（上方为 mysql 原始报错）"
    MYSQL_STATE=FAIL
    MYSQL_AVAILABLE=0
  fi
}

mysql_observe_nonnegative() {
  mysql_label=$1
  mysql_sql=$2
  MYSQL_LAST=''
  if MYSQL_LAST=$("$MYSQL_BIN" --protocol=TCP -h 127.0.0.1 -P 3306 -u root -N -B hmdp -e "$mysql_sql"); then
    case "$MYSQL_LAST" in
      ''|*[!0-9]*)
        printf '        %s\n' "$MYSQL_LAST"
        bad "${mysql_label} 不是非负整数"
        MYSQL_STATE=FAIL
        ;;
      *) ok "${mysql_label} = ${MYSQL_LAST}（实测观测值）" ;;
    esac
  else
    bad "${mysql_label} 查询失败（上方为 mysql 原始报错）"
    MYSQL_STATE=FAIL
    MYSQL_AVAILABLE=0
  fi
}

redis_pattern_count() {
  redis_pattern=$1
  REDIS_SCAN=''
  if REDIS_SCAN=$("$REDIS_BIN" -h 127.0.0.1 -p 6379 --scan --pattern "$redis_pattern"); then
    case "$REDIS_SCAN" in
      *'(error)'*|*'AUTH failed'*|*'NOAUTH'*|*'Could not connect'*)
        printf '%s\n' "$REDIS_SCAN"
        bad "Redis 扫描 $redis_pattern 返回错误（上方为 redis-cli 原始响应）"
        REDIS_STATE=FAIL
        REDIS_LAST=''
        ;;
      *) REDIS_LAST=$(vlines "$REDIS_SCAN") ;;
    esac
  else
    bad "Redis 扫描 $redis_pattern 失败（上方为 redis-cli 原始报错）"
    REDIS_STATE=FAIL
    REDIS_LAST=''
  fi
}

redis_expect_count() {
  redis_pattern=$1
  redis_expected=$2
  redis_pattern_count "$redis_pattern"
  if [ -n "$REDIS_LAST" ]; then
    if [ "$REDIS_LAST" = "$redis_expected" ]; then
      ok "$redis_pattern = $REDIS_LAST"
    else
      bad "${redis_pattern} = ${REDIS_LAST}（期望 ${redis_expected}）"
      REDIS_STATE=FAIL
    fi
  fi
}

rabbit_probe_queue() {
  rabbit_queue=$1
  rabbit_encoded=$2
  RMQ_BODY=''
  RMQ_MESSAGES=''
  RMQ_CONSUMERS=''
  RMQ_DURABLE=''
  rabbit_url="http://127.0.0.1:15672/api/queues/%2F/$rabbit_encoded"
  RMQ_RAW=''
  if ! RMQ_RAW=$(curl --silent --show-error --noproxy '*' \
      -u "$RABBITMQ_USER:$RABBITMQ_PASS" \
      -w $'\n%{http_code}' "$rabbit_url"); then
    bad "RabbitMQ 队列 $rabbit_queue 请求失败（上方为 curl 原始报错）"
    RABBIT_STATE=FAIL
    return
  fi

  RMQ_CODE=${RMQ_RAW##*$'\n'}
  RMQ_BODY=${RMQ_RAW%$'\n'*}
  if [ "$RMQ_CODE" != "200" ]; then
    bad "RabbitMQ 队列 ${rabbit_queue} HTTP ${RMQ_CODE}（期望 200）；原始响应片段："
    printf '        %.500s\n' "$RMQ_BODY"
    RABBIT_STATE=FAIL
    return
  fi

  # 单队列响应既有顶层 name，也可能在 consumer_details[].queue.name 重复同一队列名；
  # consumers 变化会改变重复次数，因此名称只要求精确命中至少一次，不能钉死次数。
  RMQ_NAME_HITS=$(printf '%s\n' "$RMQ_BODY" | grep -o '"name"[[:space:]]*:[[:space:]]*"'"$rabbit_queue"'"' | grep -c '')
  RMQ_MESSAGES=$(printf '%s\n' "$RMQ_BODY" | grep -o '"messages"[[:space:]]*:[[:space:]]*[0-9][0-9]*' | sed 's/.*:[[:space:]]*//')
  RMQ_CONSUMERS=$(printf '%s\n' "$RMQ_BODY" | grep -o '"consumers"[[:space:]]*:[[:space:]]*[0-9][0-9]*' | sed 's/.*:[[:space:]]*//')
  RMQ_DURABLE=$(printf '%s\n' "$RMQ_BODY" | grep -o '"durable"[[:space:]]*:[[:space:]]*\(true\|false\)' | sed 's/.*:[[:space:]]*//')

  if [ "$RMQ_NAME_HITS" -lt 1 ] || [ "$(vlines "$RMQ_MESSAGES")" != "1" ] || \
     [ "$(vlines "$RMQ_CONSUMERS")" != "1" ] || [ "$(vlines "$RMQ_DURABLE")" != "1" ]; then
    bad "RabbitMQ 队列 $rabbit_queue 虽为 HTTP 200，但队列名/字段无法唯一解析；原始响应片段："
    printf '        %.500s\n' "$RMQ_BODY"
    RABBIT_STATE=FAIL
    RMQ_MESSAGES=''
    RMQ_CONSUMERS=''
    RMQ_DURABLE=''
    return
  fi

  ok "HTTP 200 且响应中的队列名为 ${rabbit_queue}（精确命中 ${RMQ_NAME_HITS} 次；字段顺序无关）"
}

echo "仓库：$REPO"
echo "只读体检目标：MySQL 127.0.0.1:3306/hmdp；Redis 127.0.0.1:6379；RabbitMQ 127.0.0.1:15672；后端 127.0.0.1:18081"

# ── 1. 库形态 ──────────────────────────────────────────────────────────
hdr '① 库形态：8 张在用表逐表计数 + 用户角色'
if [ -z "${MYSQL_PWD:-}" ]; then
  info '未提供 MYSQL_PWD，MySQL ①–④ 均无法判定。'
  printf "        export MYSQL_PWD='your_password_here'  # 换成你的 MySQL 口令\n"
elif [ ! -x "$MYSQL_BIN" ]; then
  bad "MySQL 客户端不存在或不可执行：$MYSQL_BIN"
  MYSQL_STATE=FAIL
  mysql_hint
else
  MYSQL_SELF=''
  if MYSQL_SELF=$("$MYSQL_BIN" --protocol=TCP -h 127.0.0.1 -P 3306 -u root -N -B hmdp -e 'SELECT COUNT(*) FROM tb_user;'); then
    if [ "$MYSQL_SELF" = "1013" ]; then
      ok "自证：tb_user 为已知非零基数 ${MYSQL_SELF}，确认读到真实数据"
      MYSQL_STATE=OK
      MYSQL_AVAILABLE=1
    else
      bad "自证：tb_user = ${MYSQL_SELF}（期望已知非零基数 1013），后续结果不采信"
      MYSQL_STATE=FAIL
    fi
  else
    bad 'MySQL 连接/认证失败（上方为 mysql 原始报错）'
    MYSQL_STATE=FAIL
    mysql_hint
  fi

  if [ "$MYSQL_AVAILABLE" = "1" ]; then
    MYSQL_NEG=''
    if MYSQL_NEG=$("$MYSQL_BIN" --protocol=TCP -h 127.0.0.1 -P 3306 -u root -N -B hmdp \
        -e 'SELECT COUNT(*) FROM __demo_readiness_no_such_table__;' 2>&1); then
      bad '反向自证失败：不存在的表竟然查询成功，后续 MySQL 的 0 不采信'
      MYSQL_STATE=FAIL
      MYSQL_AVAILABLE=0
    elif printf '%s\n' "$MYSQL_NEG" | grep -q 'ERROR 1146' && \
         printf '%s\n' "$MYSQL_NEG" | grep -q '__demo_readiness_no_such_table__'; then
      printf '%s\n' "$MYSQL_NEG"
      ok '反向自证：不存在的表查询明确报 ERROR 1146（上方原始报错可见），与真实计数 0 可区分'
    else
      printf '%s\n' "$MYSQL_NEG"
      bad '反向自证失败：错误不是探针表的 ERROR 1146，后续 MySQL 的 0 不采信'
      MYSQL_STATE=FAIL
      MYSQL_AVAILABLE=0
    fi
  fi
fi

if [ "$MYSQL_AVAILABLE" = "1" ]; then
  mysql_expect 'hmdp 表数' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='hmdp' AND table_type='BASE TABLE';" 8
  mysql_expect 'tb_user' 'SELECT COUNT(*) FROM tb_user;' 1013
  mysql_expect 'tb_reservation' 'SELECT COUNT(*) FROM tb_reservation;' 301350
  mysql_expect 'tb_reservation_request' 'SELECT COUNT(*) FROM tb_reservation_request;' 302108
  mysql_expect 'tb_reservation_batch' 'SELECT COUNT(*) FROM tb_reservation_batch;' 23
  mysql_expect 'tb_laboratory' 'SELECT COUNT(*) FROM tb_laboratory;' 4
  mysql_expect 'tb_equipment' 'SELECT COUNT(*) FROM tb_equipment;' 12
  mysql_expect 'tb_equipment_category' 'SELECT COUNT(*) FROM tb_equipment_category;' 6
  mysql_observe_nonnegative 'tb_reservation_dlq' 'SELECT COUNT(*) FROM tb_reservation_dlq;'
  DLQ_COUNT=$MYSQL_LAST
  mysql_expect 'tb_user.role = USER' "SELECT COUNT(*) FROM tb_user WHERE role='USER';" 1011
  mysql_expect 'tb_user.role = LAB_ADMIN' "SELECT COUNT(*) FROM tb_user WHERE role='LAB_ADMIN';" 2
  mysql_expect 'tb_user 未知角色数' "SELECT COUNT(*) FROM tb_user WHERE role NOT IN ('USER','LAB_ADMIN') OR role IS NULL;" 0
  mysql_expect 'tb_reservation 生命周期列 status/quota_released/active_flag' "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='hmdp' AND table_name='tb_reservation' AND column_name IN ('status','quota_released','active_flag');" 3
elif [ "$MYSQL_STATE" = "SKIP" ]; then
  info '① 未判定：缺少 MySQL 凭据。'
fi

# ── 2. 今天可成功核验的预约 ─────────────────────────────────────────────
hdr '② 今天可成功核验的预约数'
if [ "$MYSQL_AVAILABLE" = "1" ]; then
  mysql_observe_nonnegative 'RESERVED 且批次已开始' "SELECT COUNT(*) FROM tb_reservation r JOIN tb_reservation_batch b ON r.batch_id=b.id WHERE r.status='RESERVED' AND b.start_time <= NOW();"
  VERIFY_COUNT=$MYSQL_LAST
  mysql_observe_nonnegative 'start_time <= NOW() 的已开始批次数' 'SELECT COUNT(*) FROM tb_reservation_batch WHERE start_time <= NOW();'
  STARTED_BATCH_COUNT=$MYSQL_LAST
  if [ "$VERIFY_COUNT" = "0" ]; then
    printf '  实测依据：已开始批次 %s 个，其中 RESERVED 预约 %s 条。\n' "$STARTED_BATCH_COUNT" "$VERIFY_COUNT"
    printf '  Why：publish 阶段强制 booking_end_time <= start_time，而预约要求 now < booking_end_time；因此经接口预约成功时 start_time 必然还在未来，且当前压测预约仍挂在未开始批次上。\n'
  fi
elif [ "$MYSQL_STATE" = "SKIP" ]; then
  info '② 未判定：缺少 MySQL 凭据。'
else
  bad '② 无法判定：MySQL 自证、连接或查询失败。'
fi

# ── 3. 死信表 ──────────────────────────────────────────────────────────
hdr '③ 死信表行数'
if [ "$MYSQL_AVAILABLE" = "1" ]; then
  case "$DLQ_COUNT" in
    ''|*[!0-9]*) bad '③ 无法采信：① 的 tb_reservation_dlq 结果不是非负整数。' ;;
    *)
      ok "tb_reservation_dlq = ${DLQ_COUNT}（复用①的同一查询结果）"
      if [ "$DLQ_COUNT" = "0" ]; then
        printf '  说明：死信/重放演示链路无活数据。\n'
      fi
      ;;
  esac
elif [ "$MYSQL_STATE" = "SKIP" ]; then
  info '③ 未判定：缺少 MySQL 凭据。'
else
  bad '③ 无法判定：MySQL 自证、连接或查询失败。'
fi

# ── 4. 学生链路与管理端归属 ────────────────────────────────────────────
hdr '④ 学生链路可见数据量 + 管理端归属口径'
if [ "$MYSQL_AVAILABLE" = "1" ]; then
  mysql_expect 'tb_reservation 总数' 'SELECT COUNT(*) FROM tb_reservation;' 301350
  RESERVATION_TOTAL=$MYSQL_LAST
  mysql_expect 'status = RESERVED' "SELECT COUNT(*) FROM tb_reservation WHERE status='RESERVED';" 301346
  mysql_expect 'status = CANCELLED' "SELECT COUNT(*) FROM tb_reservation WHERE status='CANCELLED';" 3
  mysql_expect 'status = COMPLETED' "SELECT COUNT(*) FROM tb_reservation WHERE status='COMPLETED';" 0
  mysql_expect 'status = NO_SHOW' "SELECT COUNT(*) FROM tb_reservation WHERE status='NO_SHOW';" 1
  mysql_expect '未知 status 数' "SELECT COUNT(*) FROM tb_reservation WHERE status NOT IN ('RESERVED','CANCELLED','COMPLETED','NO_SHOW') OR status IS NULL;" 0
  mysql_expect 'tb_reservation_request 总数' 'SELECT COUNT(*) FROM tb_reservation_request;' 302108
  REQUEST_TOTAL=$MYSQL_LAST
  mysql_expect '管理员 13900000001 名下预约（实验室 1,2）' "SELECT COUNT(*) FROM tb_reservation r JOIN tb_reservation_batch b ON r.batch_id=b.id JOIN tb_equipment e ON b.equipment_id=e.id JOIN tb_laboratory l ON e.laboratory_id=l.id WHERE l.admin_user_id=(SELECT id FROM tb_user WHERE phone='13900000001');" 301344
  ADMIN_ONE_COUNT=$MYSQL_LAST
  mysql_expect '管理员 13900000002 名下预约（实验室 3,4）' "SELECT COUNT(*) FROM tb_reservation r JOIN tb_reservation_batch b ON r.batch_id=b.id JOIN tb_equipment e ON b.equipment_id=e.id JOIN tb_laboratory l ON e.laboratory_id=l.id WHERE l.admin_user_id=(SELECT id FROM tb_user WHERE phone='13900000002');" 6
  ADMIN_TWO_COUNT=$MYSQL_LAST
else
  if [ "$MYSQL_STATE" = "SKIP" ]; then
    info '④ 未判定：缺少 MySQL 凭据。'
  else
    bad '④ 无法判定：MySQL 自证、连接或查询失败。'
  fi
fi

# ── 5. Redis 键族 ──────────────────────────────────────────────────────
hdr '⑤ Redis 键族（8 个 pattern + DBSIZE）'
if [ -z "${REDISCLI_AUTH:-}" ]; then
  info '未提供 REDISCLI_AUTH，Redis 无法判定。'
  printf "        export REDISCLI_AUTH='<Redis 口令>'\n"
elif [ ! -x "$REDIS_BIN" ]; then
  bad "Redis 客户端不存在或不可执行：$REDIS_BIN"
  REDIS_STATE=FAIL
  redis_hint
else
  REDIS_PING=''
  if REDIS_PING=$("$REDIS_BIN" -h 127.0.0.1 -p 6379 PING); then
    if [ "$REDIS_PING" = "PONG" ]; then
      ok 'Redis 连接/认证成功（PING = PONG）'
      REDIS_STATE=OK
    else
      printf '        %s\n' "$REDIS_PING"
      bad 'Redis 连接/认证未返回 PONG（上方为 redis-cli 原始响应）'
      REDIS_STATE=FAIL
      redis_hint
    fi
  else
    bad 'Redis 连接/认证失败（上方为 redis-cli 原始报错）'
    REDIS_STATE=FAIL
    redis_hint
  fi
fi

if [ "$REDIS_STATE" = "OK" ]; then
  REDIS_DBSIZE=''
  if REDIS_DBSIZE=$("$REDIS_BIN" -h 127.0.0.1 -p 6379 DBSIZE); then
    case "$REDIS_DBSIZE" in
      ''|*[!0-9]*)
        printf '        %s\n' "$REDIS_DBSIZE"
        bad 'Redis DBSIZE 不是非负整数，后续 0 不采信'
        REDIS_STATE=FAIL
        ;;
      0)
        bad 'Redis 自证失败：DBSIZE = 0，无法用非零数据证明 icr:order:* 的 0'
        REDIS_STATE=FAIL
        ;;
      *) ok "Redis 自证：DBSIZE = ${REDIS_DBSIZE}（> 0）" ;;
    esac
  else
    bad 'Redis DBSIZE 失败（上方为 redis-cli 原始报错）'
    REDIS_STATE=FAIL
  fi
fi

if [ "$REDIS_STATE" = "OK" ]; then
  redis_expect_count 'reservation:batch:quota:*' 19; REDIS_QUOTA=$REDIS_LAST
  redis_expect_count 'reservation:batch:users:*' 13; REDIS_USERS=$REDIS_LAST
  redis_expect_count 'icr:reservation:*' 4; REDIS_ICR_RESERVATION=$REDIS_LAST
  redis_expect_count 'login:code:*' 0; REDIS_LOGIN_CODE=$REDIS_LAST
  redis_expect_count 'login:token:*' 0; LOGIN_TOKEN_COUNT=$REDIS_LAST
  redis_expect_count 'lab:equipment:*' 0; REDIS_LAB_EQUIPMENT=$REDIS_LAST
  redis_expect_count 'lock:*' 0; REDIS_LOCK=$REDIS_LAST
  redis_expect_count 'icr:order:*' 0; REDIS_ICR_ORDER=$REDIS_LAST

  if [ "$REDIS_STATE" = "OK" ]; then
    REDIS_KNOWN_SUM=$((REDIS_QUOTA + REDIS_USERS + REDIS_ICR_RESERVATION))
    if [ "$REDIS_KNOWN_SUM" = "$REDIS_DBSIZE" ]; then
      ok "自证闭环：${REDIS_QUOTA} + ${REDIS_USERS} + ${REDIS_ICR_RESERVATION} = DBSIZE ${REDIS_DBSIZE}；icr:order:* = ${REDIS_ICR_ORDER} 可采信"
    else
      bad "键族和 = ${REDIS_KNOWN_SUM}，但 DBSIZE = ${REDIS_DBSIZE}；存在未覆盖键，零值判据不采信"
      REDIS_STATE=FAIL
    fi
  fi
fi

# ── 6. RabbitMQ 队列 ───────────────────────────────────────────────────
hdr '⑥ RabbitMQ 两个 durable 队列'
if [ -z "${RABBITMQ_USER:-}" ] || [ -z "${RABBITMQ_PASS:-}" ]; then
  info '未同时提供 RABBITMQ_USER / RABBITMQ_PASS，RabbitMQ 无法判定。'
  printf "        export RABBITMQ_USER='<RabbitMQ 用户名>'\n"
  printf "        export RABBITMQ_PASS='your_password_here'  # 换成你的 RabbitMQ 口令\n"
else
  RABBIT_STATE=OK
  rabbit_probe_queue 'reservation.create.queue' 'reservation.create.queue'
  QUEUE_MESSAGES=$RMQ_MESSAGES
  QUEUE_CONSUMERS=$RMQ_CONSUMERS
  QUEUE_DURABLE=$RMQ_DURABLE
  rabbit_probe_queue 'reservation.create.dlq' 'reservation.create.dlq'
  DLQ_MESSAGES=$RMQ_MESSAGES
  DLQ_CONSUMERS=$RMQ_CONSUMERS
  DLQ_DURABLE=$RMQ_DURABLE

  if [ "$RABBIT_STATE" = "OK" ]; then
    if [ "$QUEUE_MESSAGES" = "0" ] && [ "$QUEUE_CONSUMERS" = "1" ] && [ "$QUEUE_DURABLE" = "true" ]; then
      ok "reservation.create.queue：messages=${QUEUE_MESSAGES}，consumers=${QUEUE_CONSUMERS}，durable=${QUEUE_DURABLE}"
    else
      bad "reservation.create.queue：messages=${QUEUE_MESSAGES} / consumers=${QUEUE_CONSUMERS} / durable=${QUEUE_DURABLE}（期望 0/1/true）"
      RABBIT_STATE=FAIL
    fi
    if [ "$DLQ_MESSAGES" = "0" ] && [ "$DLQ_CONSUMERS" = "1" ] && [ "$DLQ_DURABLE" = "true" ]; then
      ok "reservation.create.dlq：messages=${DLQ_MESSAGES}，consumers=${DLQ_CONSUMERS}，durable=${DLQ_DURABLE}；consumers=1 是 DLQ 落库消费者的正常状态"
    else
      bad "reservation.create.dlq：messages=${DLQ_MESSAGES} / consumers=${DLQ_CONSUMERS} / durable=${DLQ_DURABLE}（期望 0/1/true）"
      RABBIT_STATE=FAIL
    fi
  fi
  if [ "$RABBIT_STATE" = "FAIL" ]; then rabbit_hint; fi
fi

# ── 7. 汇总 ────────────────────────────────────────────────────────────
hdr '⑦ 今天能演 / 演不了 / 无法判定'
BACKEND_CODE=''
if BACKEND_CODE=$(curl --silent --show-error --noproxy '*' -o /dev/null -w '%{http_code}' 'http://127.0.0.1:18081/'); then
  case "$BACKEND_CODE" in
    [0-9][0-9][0-9])
      if [ "$BACKEND_CODE" = "000" ]; then
        bad '后端演不了：未取得 HTTP 状态码（HTTP 000）。'
        BACKEND_STATE=FAIL
      else
        ok "已判定：后端能响应（HTTP ${BACKEND_CODE}；非 2xx 也证明服务已监听）。"
        BACKEND_STATE=OK
      fi
      ;;
    *)
      bad "后端演不了：HTTP 探针返回无法解析的状态码：$BACKEND_CODE"
      BACKEND_STATE=FAIL
      ;;
  esac
else
  bad '后端演不了：连接 127.0.0.1:18081 失败（上方为 curl 原始报错）。'
  BACKEND_STATE=FAIL
fi

if [ "$MYSQL_STATE" = "OK" ] && [ "$MYSQL_AVAILABLE" = "1" ]; then
  ok "已判定：学生数据链路有 $RESERVATION_TOTAL 条预约、$REQUEST_TOTAL 条请求，可演列表/状态分布。"
  ok "已判定：管理端 ${ADMIN_ONE_COUNT} 的口径是 phone='13900000001' 的管理员名下实验室 1,2；另一管理员为 ${ADMIN_TWO_COUNT}，全表为 ${RESERVATION_TOTAL}。"
  if [ "$VERIFY_COUNT" = "0" ]; then
    warn '今天演不了成功核验链路：当前有 0 条 RESERVED 且 start_time <= NOW() 的预约；原因见② Why。'
  else
    ok "已判定：成功核验链路有 $VERIFY_COUNT 条可用预约。"
  fi
  if [ "$DLQ_COUNT" = "0" ]; then
    warn '今天演不了死信/重放链路：tb_reservation_dlq 中无活数据；这是当前健康状态，不置失败。'
  else
    ok "已判定：死信表有 $DLQ_COUNT 条活数据，可演死信/重放。"
  fi
elif [ "$MYSQL_STATE" = "SKIP" ]; then
  info '无法判定：缺少 MYSQL_PWD，数据库相关演示能力均未探测。'
else
  bad '无法判定：MySQL 连接、自证、数据形态或查询失败，数据库相关演示能力不能报绿。'
fi

if [ "$REDIS_STATE" = "OK" ]; then
  ok '已判定：Redis 在用键族与 DBSIZE 闭环，课程时代 icr:order:* 已清零。'
  if [ "$LOGIN_TOKEN_COUNT" = "0" ]; then
    ok '已判定：login:token:* = 0，无存量登录会话；演示时必须现场登录获取新会话。'
  fi
elif [ "$REDIS_STATE" = "SKIP" ]; then
  info '无法判定：缺少 REDISCLI_AUTH，Redis 键族及登录会话状态未探测。'
else
  bad '无法判定：Redis 连接、自证或键族闭环失败，Redis 相关演示能力不能报绿。'
fi

if [ "$RABBIT_STATE" = "OK" ]; then
  ok '已判定：两个 RabbitMQ 队列均可读、durable、messages=0、consumers=1；DLQ 的 1 个消费者是正常落库消费者。'
elif [ "$RABBIT_STATE" = "SKIP" ]; then
  info '无法判定：缺少 RABBITMQ_USER / RABBITMQ_PASS，队列状态未探测。'
else
  bad '无法判定：RabbitMQ HTTP、认证、队列名自证或字段解析失败，消息链路不能报绿。'
fi

if [ "$FAIL" = "0" ]; then
  printf '\n结论：体检完成；所有已执行探测均通过，SKIP 项仍保持“无法判定”。\n'
else
  printf '\n结论：体检发现 FAIL；上面标出的链路演不了或无法判定。\n'
fi
exit "$FAIL"
