#!/usr/bin/env bash
#
# 研约通（yanyuetong）终验清单 —— 可复跑版本。
# 对应 docs/refactor/PHASE10-DESIGN.md §3.13；改这里的判据前请先读那一节的教训。
#
# 用法（在仓库根目录）：
#     bash scripts/verify-acceptance.sh
#     SCAN_SECRETS='真实口令1 真实口令2' bash scripts/verify-acceptance.sh   # 额外的字面量检查
#     PHASE_BASE=<某个 commit> bash scripts/verify-acceptance.sh             # 额外的"禁改清单"检查
#
# ── 三条硬性约定（都是踩过的坑，不是风格偏好）─────────────────────────────
# 1. 文件清单可用 `find` 或 `git -c core.quotepath=false ls-files` 生成；用于凭据扫描时，
#    清单语义必须是“已入库”，且路径必须是真实 UTF-8。本仓库有 3 个非 ASCII 文件名；
#    裸 `git ls-files` 在默认 `core.quotepath=true` 下会把它们输出成八进制转义（如 "\347\247\222..."），
#    交给 grep 会打不开文件而被**静默跳过** —— 实测恰好漏掉过含真口令的那份交付报告。
# 2. 不写 `2>/dev/null`。探测器的报错必须浮出来，否则"0 命中"与"根本没读到"分不清。
# 3. 每个探测器先自证（在一个已知存在的串上命中），自证不过就 FAIL，不采信它随后的 0。
#
# ── 为什么判据里带白名单 ────────────────────────────────────────────────
# 下面两处白名单是**实测值**，不是推断值。当初 §3.13 第 3 条记的是"26 个关键词全部 0 命中"，
# 但按它写的范围（`find src pom.xml`）在当前树上实测并非 0：SQL 的 `LIKE` 语法 1 处、
# DROP 脚本里 9 处中文表名注释。这两处在记录基线时**就已经存在**，所以那句"全部 0 命中"
# 是范围没写清造成的假结论。本脚本把白名单连同**期望条数**一起钉住 —— 期望条数一旦变化就会红。
# 上述是 §3.13 第 3 条的两处关键词白名单；D 段另有凭据白名单：出厂默认 guest 2 行，
# 以及大写日志词（PASS/FAIL/SKIP/OK 后紧跟冒号）1 行，不要混读。G 段范围是
# `git -c core.quotepath=false ls-files --others --exclude-standard` 列出的“未跟踪且未被忽略”文件。

set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO" || { echo "无法进入仓库根目录：$REPO"; exit 1; }

FAIL=0
hdr()  { printf '\n== %s ==\n' "$1"; }
ok()   { printf '  [PASS] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; FAIL=1; }
info() { printf '  [SKIP] %s\n' "$1"; }

# ── 文件清单 ────────────────────────────────────────────────────────────
# 用 `git -c core.quotepath=false ls-files`（§3.13 第 5 条允许的两种方式之一）：它给出
# **已入库**文件且路径是真正的 UTF-8。不用裸 `find` 的原因本轮实测过 —— `find src` 会把
# 未跟踪的 `src/main/resources/application-local.yaml`（真实口令所在、已被 .gitignore 忽略）
# 一并扫进来，让"凭据探测器"报出一堆假阳性。判据问的是"入库文件里有没有"，清单就必须是入库文件。
# 关掉 quotepath 是为了拿到真实中文路径：默认开启时 git 会输出八进制转义，grep 打不开会**静默跳过**。
# 在给定清单上跑 grep -n，输出匹配行（清单按 NUL 分隔传给 xargs）
scan() { printf '%s\n' "$1" | tr '\n' '\0' | xargs -0 grep -nE "$2"; }
scanl() { printf '%s\n' "$1" | tr '\n' '\0' | xargs -0 grep -niE "$2"; }
count() { wc -l | tr -d ' '; }
# 数一个**变量里**的行数。不能直接 `printf '%s' "$x" | wc -l`：命令替换会吃掉末尾换行，
# 9 行会被数成 8（本轮实测踩过）。空值必须返回 0。
vlines() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | grep -c ''; fi; }

TRACKED=$(git -c core.quotepath=false ls-files)
CODE_FILES=$(printf '%s\n' "$TRACKED" | grep -E '^(src/.*\.(java|xml|yaml|yml|lua))$' | sort)
SQL_FILES=$(printf '%s\n' "$TRACKED" | grep -E '^src/main/resources/db/.*\.sql$' | sort)
# 凭据探测器的范围是**全部已入库文本文件**（含 docs/ 的 .md）——T6 实测的 8 处泄漏有 6 处在文档里，
# 只扫 src/ 会把当年真正出问题的地方排除在外。
CRED_FILES=$(printf '%s\n' "$TRACKED" | grep -E '\.(java|xml|yaml|yml|lua|sql|md|sh|properties|example)$' | sort)
N_CODE=$(vlines "$CODE_FILES")
N_SQL=$(vlines "$SQL_FILES")
N_CRED=$(vlines "$CRED_FILES")

echo "仓库：$REPO"
echo "代码/配置清单 ${N_CODE} 个文件（java/xml/yaml/yml/lua，已入库）；SQL 脚本 ${N_SQL} 个；凭据扫描范围 ${N_CRED} 个文件"

# ── A. 自证：清单确实被读到了 ───────────────────────────────────────────
hdr 'A 扫描器自证'
if [ "$N_CODE" -lt 50 ] || [ "$N_SQL" -lt 5 ]; then
  bad "清单过短（代码 ${N_CODE} / SQL ${N_SQL}），文件清单没读到，后续一切 0 命中不予采信"
  printf '\n结论：INVALID（自证失败）\n'; exit 1
fi
SELF_A=$(printf '%s\n' "$CODE_FILES" | tr '\n' '\0' | xargs -0 grep -l 'com\.yanyuetong' | count)
if [ "$SELF_A" -ge 50 ]; then
  ok "自证 A：$SELF_A 个代码文件含 com.yanyuetong（应 ≥50）—— 清单确实被读到"
else
  bad "自证 A：只有 $SELF_A 个文件含 com.yanyuetong（应 ≥50）"
fi
SELF_B=$(printf '%s\n' "$TRACKED" | LC_ALL=C grep -c '[^ -~]')
if [ "$SELF_B" -ge 1 ]; then
  ok "自证 B：清单含 $SELF_B 个非 ASCII 路径（正是裸 git ls-files 默认会转义的那批）"
else
  bad "自证 B：清单里没有非 ASCII 路径，说明 quotepath 没关掉或清单退化了"
fi
SELF_C=$(printf '%s\n' "$CODE_FILES" | tr '\n' '\0' | xargs -0 grep -l 'zzz-no-such-string-zzz' | count)
if [ "$SELF_C" -eq 0 ]; then
  ok "自证 C：用不存在的串扫描命中 0 —— 探测器不是恒真"
else
  bad "自证 C：不存在的串竟然命中 $SELF_C 次"
fi

# ── B. 旧命名零残留 ─────────────────────────────────────────────────────
hdr 'B 旧命名零残留（com.hmdp / hm-dianping / HmDianPing）'
HITS_B=$(scan "$CODE_FILES
pom.xml" 'com\.hmdp|hm-dianping|HmDianPing')
N_B=$(vlines "$HITS_B")
if [ "$N_B" -eq 0 ]; then
  ok "0 命中（含字符串形式 \"com.hmdp 一并覆盖）"
else
  bad "$N_B 处命中："; printf '%s\n' "$HITS_B" | sed 's/^/        /'
fi
# 白名单：JDBC URL 里的库名 hmdp 是有意保留的（README §15）
N_DB=$(scan "$CODE_FILES" 'jdbc:mysql://[^"]*/hmdp' | count)
ok "库名 hmdp 仍出现在 JDBC URL（有意保留，约 $N_DB 处）"

# ── C. 黑马语义关键词 ───────────────────────────────────────────────────
hdr 'C 黑马语义关键词（23 个模式，覆盖大小写变体，词边界 + 大小写不敏感）'
# 口径说明：设计文档记为"26 个关键词"，那是把 GEO/Bitmap/Feed 三个大小写变体拆开数的；
# 这里用大小写不敏感匹配，故合成 23 个模式，覆盖面相同。
KWS='seckill 秒杀 voucher 优惠券 blog 探店 笔记 comment 评论 like 点赞 follow 关注 sign 签到 shop 商铺 geo HyperLogLog bitmap feed 达人 热门'
N_KW=0; TOTAL_C=0
for kw in $KWS; do
  N_KW=$((N_KW + 1))
  n=$(scanl "$CODE_FILES" "(^|[^a-z0-9_])${kw}([^a-z0-9_]|\$)" | count)
  if [ "$n" != "0" ]; then
    TOTAL_C=$((TOTAL_C + n))
    printf '        %-12s %s 处\n' "$kw" "$n"
  fi
done
# 注意：变量后面紧跟中文字符时必须写 ${VAR}。macOS 的 bash 3.2 在 UTF-8 下会把
# 全角字符吸进变量名（`$N_KW；` 会被解析成变量名 `N_KW；` → unbound variable）。
echo "  关键词数：${N_KW}；代码/配置范围命中合计：${TOTAL_C}"
# 白名单 1：设备名搜索的 SQL `LIKE` 语法（EquipmentMapper.xml，README §15 说明为有意选择）
W1=$(scan "$CODE_FILES" '(^|[^a-z0-9_])[Ll][Ii][Kk][Ee]([^a-z0-9_]|$)' | grep -c 'EquipmentMapper\.xml' )
if [ "$TOTAL_C" = "1" ] && [ "$W1" = "1" ]; then
  ok "代码/配置范围命中恰好 1 处，且是白名单项（EquipmentMapper 的 LIKE 语法）"
else
  bad "代码/配置范围命中 $TOTAL_C 处（期望恰好 1 处白名单），其中 EquipmentMapper 的 LIKE 有 $W1 处"
fi
# 白名单 2：只有 DROP 脚本允许写出课程表名（它是全仓库唯一集中记录课程表名的地方）
SQL_KW=$(scan "$SQL_FILES" '秒杀|优惠券|探店|笔记|评论|关注|签到|商铺')
FILES_KW=$(printf '%s\n' "$SQL_KW" | sed 's/:.*//' | sort -u | grep -v '^$' | wc -l | tr -d ' ')
DROP_ONLY=$(printf '%s\n' "$SQL_KW" | grep -c 'phase10_drop_legacy_tables\.sql')
N_SQL_KW=$(vlines "$SQL_KW")
if [ "$N_SQL_KW" = "9" ] && [ "$FILES_KW" = "1" ] && [ "$DROP_ONLY" = "9" ]; then
  ok "SQL 范围命中 $N_SQL_KW 处，全部落在 phase10_drop_legacy_tables.sql（白名单，有意保留）"
else
  bad "SQL 范围命中 $N_SQL_KW 处、分布在 $FILES_KW 个文件，其中 DROP 脚本 $DROP_ONLY 处（期望 9/1/9）："
  printf '%s\n' "$SQL_KW" | sed 's/^/        /'
fi

# ── D. 凭据 ─────────────────────────────────────────────────────────────
hdr 'D 凭据（结构性探测器 + 可选的字面量探测器）'
PLACEHOLDER='change_me|your_password_here|\$\{|PLACEHOLDER|placeholder'
CRED_PATTERN='(pass(word|wd)?|pwd)[[:space:]]*[:=][[:space:]]*[^[:space:]]+'
# 探测器自证：范围里必须能找到"口令"这个词本身，否则 0 命中说明不了任何事
if [ "$(printf '%s\n' "$CRED_FILES" | tr '\n' '\0' | xargs -0 grep -l -i 'password' | count)" -ge 1 ]; then
  ok "自证 D：范围内确实存在 password 字样（探测器不是恒假）"
else
  bad "自证 D：范围里连一处 password 都没有，说明清单退化，0 命中不予采信"
fi
D2=$(printf '%s\n' "$CRED_FILES" | tr '\n' '\0' | xargs -0 grep -nEi "$CRED_PATTERN" | grep -viE "$PLACEHOLDER")
D2_GUEST=$(printf '%s\n' "$D2" | grep 'guest')
D2_LOG=$(printf '%s\n' "$D2" | grep -E '(PASS|FAIL|SKIP|OK):')
N_D2_GUEST=$(vlines "$D2_GUEST")
N_D2_LOG=$(vlines "$D2_LOG")
echo "  白名单位置（仅文件:行号）："
if [ -n "$D2_GUEST" ]; then
  printf '%s\n' "$D2_GUEST" | sed -nE 's/^([^:]+:[0-9]+):.*/        guest  \1/p'
fi
if [ -n "$D2_LOG" ]; then
  printf '%s\n' "$D2_LOG" | sed -nE 's/^([^:]+:[0-9]+):.*/        日志词 \1/p'
fi
if [ "$N_D2_GUEST" = "2" ] && [ "$N_D2_LOG" = "1" ]; then
  ok "结构性探测器白名单条数：RabbitMQ 出厂默认 guest 2 行；大写日志词（PASS/FAIL/SKIP/OK 后紧跟冒号）1 行"
else
  bad "白名单条数变化：RabbitMQ 出厂默认 guest ${N_D2_GUEST} 行（期望 2）；大写日志词（PASS/FAIL/SKIP/OK 后紧跟冒号）${N_D2_LOG} 行（期望 1）"
fi
D2_NONWHITE=$(printf '%s\n' "$D2" | grep -v 'guest' | grep -vE '(PASS|FAIL|SKIP|OK):')
if [ -z "$D2_NONWHITE" ]; then
  ok "结构性探测器：非白名单命中 0（白名单 = RabbitMQ 出厂默认 guest + 大写日志词（PASS/FAIL/SKIP/OK 后紧跟冒号）；见 PHASE10-DESIGN §2.2 订正二）"
else
  bad "结构性探测器发现非白名单命中："
  # 凭据风险只报位置与计数；命中行含真实值，回显会把值放大进日志。
  printf '%s\n' "$D2_NONWHITE" | sed -nE 's/^([^:]+:[0-9]+):.*/        \1/p'
fi
if [ -n "${SCAN_SECRETS:-}" ]; then
  MISS=0; N_SEC=0
  for s in $SCAN_SECRETS; do
    N_SEC=$((N_SEC + 1))
    n=$(printf '%s\n' "$CRED_FILES" | tr '\n' '\0' | xargs -0 grep -F -l -- "$s" | count)
    if [ "$n" != "0" ]; then bad "字面量命中（值不复述）：第 ${N_SEC} 个口令 → ${n} 个文件"; MISS=1; fi
  done
  [ "$MISS" = "0" ] && ok "字面量探测器：提供的 ${N_SEC} 个口令全部 0 命中"
else
  info "字面量探测器未运行 —— 本脚本自身入库，故不内嵌任何口令字面量。"
  info "要查请显式提供：SCAN_SECRETS='值1 值2' bash scripts/verify-acceptance.sh"
fi

# ── E. 仓库形态 ─────────────────────────────────────────────────────────
hdr 'E 仓库形态'
ROOT_FILES=$(git ls-files | grep -v '/' | LC_ALL=C sort | tr '\n' ' ')
if [ "$ROOT_FILES" = ".gitignore README.md pom.xml " ]; then
  ok "根目录入库文件只有 .gitignore / README.md / pom.xml"
else
  bad "根目录入库文件不是预期的三个：$ROOT_FILES"
fi
SEC=$(grep -c '^## ' README.md)
if [ "$SEC" = "15" ]; then
  ok "README.md 有 $SEC 节"
else
  bad "README.md 有 $SEC 节（期望 15）"
fi
N_DOCS=$(find docs -type f -name '*.md' | count)
ok "docs/ 下 $N_DOCS 份 markdown"

# ── F. 禁改清单（需要 PHASE_BASE）───────────────────────────────────────
hdr 'F 禁改清单（相对某个 base commit）'
if [ -n "${PHASE_BASE:-}" ]; then
  CHANGED=0
  # 路径一律先用 find 定位，不凭记忆拼（§3.13 第 4 条）
  for name in reservation.lua reservationRollback.lua unlock.lua \
              ReservationLifecycleServiceImpl ReservationPersistenceServiceImpl \
              ReservationQuotaRollbackExecutor ReservationQuotaExecutor \
              ReservationRequestStatus ReservationStatus; do
    # 名字可能是类名，也可能是完整文件名（含 .lua），三种后缀都试一遍
    p=$(find src -type f \( -name "$name" -o -name "${name}.java" -o -name "${name}.lua" \) | head -1)
    if [ -z "$p" ]; then bad "找不到 $name 的真实路径"; CHANGED=1; continue; fi
    if [ -n "$(git diff --name-only "$PHASE_BASE"..HEAD -- "$p")" ]; then
      bad "禁改文件被改动：$p"
      CHANGED=1
    fi
  done
  [ "$CHANGED" = "0" ] && ok "9 个禁改文件相对 $PHASE_BASE 均未改动（active_flag DDL 见 SQL 脚本人工核对）"
else
  info "未提供 PHASE_BASE，跳过。用法：PHASE_BASE=<commit> bash scripts/verify-acceptance.sh"
fi

# ── G. 未跟踪且未被忽略的文件 ─────────────────────────────────────────
hdr 'G 未跟踪且未被忽略的文件'
UNTRACKED=$(git -c core.quotepath=false ls-files --others --exclude-standard)
N_UNTRACKED=$(vlines "$UNTRACKED")
echo "  扫描范围 ${N_UNTRACKED} 个文件"
if [ "$N_UNTRACKED" -gt 0 ]; then
  printf '%s\n' "$UNTRACKED" | sed 's/^/        /'
fi

# 正向前置每次都在仓库外实际执行；否则未跟踪范围变空后，本段会退化成恒绿摆设。
G_VALID=0
if G_PROBE=$(mktemp /tmp/verify-acceptance.XXXXXX); then
  trap 'rm -f "$G_PROBE"' EXIT HUP INT TERM
  printf '%s%s %s\n' 'pass' 'word:' 'hunter2' > "$G_PROBE"
  G_SELF=$(grep -nE "$CRED_PATTERN" "$G_PROBE" | grep -viE "$PLACEHOLDER")
  rm -f "$G_PROBE"
  trap - EXIT HUP INT TERM
  if [ "$(vlines "$G_SELF")" -ge 1 ]; then
    ok "正向前置：/tmp 探针被结构性正则命中"
    G_VALID=1
  else
    bad "正向前置 INVALID：/tmp 探针未被结构性正则命中，范围的 0 命中不予采信"
  fi
else
  bad "正向前置 INVALID：无法在 /tmp 创建探针，范围的 0 命中不予采信"
fi

if [ "$N_UNTRACKED" -gt 0 ]; then
  G_HITS=$(printf '%s\n' "$UNTRACKED" | tr '\n' '\0' | xargs -0 grep -nEi "$CRED_PATTERN" | grep -viE "$PLACEHOLDER")
else
  G_HITS=''
fi
N_G_HITS=$(vlines "$G_HITS")
if [ "$G_VALID" = "1" ] && [ "$N_G_HITS" = "0" ]; then
  ok "结构性探测器：未跟踪且未被忽略的范围命中 0"
elif [ "$N_G_HITS" != "0" ]; then
  bad "结构性探测器发现 ${N_G_HITS} 处命中："
  # 凭据风险只报位置与计数；命中行含真实值，回显会把值放大进日志。
  printf '%s\n' "$G_HITS" | sed -nE 's/^([^:]+:[0-9]+):.*/        \1/p'
else
  bad "结构性探测器范围命中 0，但正向前置 INVALID，结果不予采信"
fi

# ── 汇总 ────────────────────────────────────────────────────────────────
if [ "$FAIL" = "0" ]; then
  printf '\n结论：ALL CHECKS PASSED\n'
else
  printf '\n结论：FAILED —— 上面标 [FAIL] 的项需要处理后重跑\n'
fi
exit "$FAIL"
