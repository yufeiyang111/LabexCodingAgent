// 唯一职责：判定「单个文件条目」是跳过、冲突还是产出一个步骤。
// 边界：纯函数；不读写文件、不发请求、不推进游标、不装配整批计划。
//
// 拆出来的原因：这套判定有 6 条互斥分支（删除/新建/占用/漂移/截断/二进制），
// 每一条都对应一个真实事故，混在计划装配里会让两侧都读不完。

export const CONFLICT_REASONS = Object.freeze({
  MISSING_LOCAL: 'missing-local',
  CONTENT_DRIFT: 'content-drift',
  LOCAL_OCCUPIED: 'local-occupied',
  TRUNCATED_BASELINE: 'truncated-baseline',
  TRUNCATED_TARGET: 'truncated-target',
  PROBE_UNKNOWN: 'probe-unknown'
})

export const SKIP_REASONS = Object.freeze({
  BINARY_CONTENT: 'binary-content'
})

const CONFLICT_MISSING_LOCAL = CONFLICT_REASONS.MISSING_LOCAL
const CONFLICT_CONTENT_DRIFT = CONFLICT_REASONS.CONTENT_DRIFT
const CONFLICT_LOCAL_OCCUPIED = CONFLICT_REASONS.LOCAL_OCCUPIED
const CONFLICT_TRUNCATED_BASELINE = CONFLICT_REASONS.TRUNCATED_BASELINE
const CONFLICT_TRUNCATED_TARGET = CONFLICT_REASONS.TRUNCATED_TARGET
const CONFLICT_PROBE_UNKNOWN = CONFLICT_REASONS.PROBE_UNKNOWN

/**
 * 服务端把文件内容当 UTF-8 文本存放（`Files.readString(UTF_8)`），二进制文件里的非法字节
 * 会在那一刻被替换成 U+FFFD，原字节长度和内容都回不来了。前端再把它写成文本，
 * 落盘的就是一段"看起来像 SQLite 头、后面整片错位"的假文件。
 *
 * 判据只认 NUL：JSON 能合法承载 `\u0000`，而真实文本文件几乎不含它；
 * U+FFFD 不能作为依据——合法文本（用户正文、文档）本来就可能包含它。
 *
 * 命中后按**跳过**处理而不是冲突：冲突意味着"用户裁决后能继续"，但这里用户裁决不了——
 * 原始字节在服务端落库那一刻就丢了。判成冲突会让 strict 策略永久卡在同一个文件上，
 * 游标推不动，整个项目再也同步不了。跳过会进 skipped 列表并在进度面板逐行显示。
 */
export function isBinaryContent(content) {
  return typeof content === 'string' && content.includes('\u0000')
}

/**
 * 单条目分类（不含策略）：返回 skip / conflict / step 三种结果之一。
 * `local` 是虚拟本地视图的当前条目：{ exists, content, unknown, createdInBatch, delivered }。
 */
export function classifyFile(file, local) {
  if (local.unknown) return { kind: 'conflict', reason: CONFLICT_PROBE_UNKNOWN }
  if (file.changeType === 'delete') {
    if (!local.exists) return { kind: 'skip', reason: 'already-absent' }
    if (file.beforeTruncated) return { kind: 'conflict', reason: CONFLICT_TRUNCATED_BASELINE }
    if (file.beforeContent !== null && local.content !== file.beforeContent) {
      return { kind: 'conflict', reason: CONFLICT_CONTENT_DRIFT }
    }
    return { kind: 'step', op: 'delete' }
  }
  if (file.afterTruncated) {
    // 目标内容本身被截断：写下去就是把残缺内容落盘，宁可停在冲突让人处理。
    return { kind: 'conflict', reason: CONFLICT_TRUNCATED_TARGET }
  }
  if (file.changeType === 'create' || file.beforeExisted === false) {
    if (!local.exists) return { kind: 'step', op: 'write' }
    // 本批次自己刚创建的文件被再次修改：继续写，不算占用冲突。
    if (local.createdInBatch) {
      if (file.afterContent !== null && local.content === file.afterContent) {
        return { kind: 'skip', reason: 'already-applied' }
      }
      return { kind: 'step', op: 'write' }
    }
    if (file.afterContent !== null && local.content === file.afterContent) {
      return { kind: 'skip', reason: 'already-applied' }
    }
    return { kind: 'conflict', reason: CONFLICT_LOCAL_OCCUPIED }
  }
  if (!local.exists) {
    // 交付过却不见了：用户删了它，不能悄悄复活。
    // 从未交付过：这是"同步到一个还没同步过的目录"，写下去不覆盖任何本地内容。
    if (!local.delivered) return { kind: 'step', op: 'write' }
    return { kind: 'conflict', reason: CONFLICT_MISSING_LOCAL }
  }
  // 目标内容已经落地：重叠窗口会重复交付同一条变更，若只看 beforeContent 就会判成漂移，
  // 让 strict 策略在每次重扫时永久卡住。先比目标态，再比基线。
  if (file.afterContent !== null && local.content === file.afterContent) {
    return { kind: 'skip', reason: 'already-applied' }
  }
  if (file.beforeTruncated) return { kind: 'conflict', reason: CONFLICT_TRUNCATED_BASELINE }
  if (file.beforeContent === null || local.content === file.beforeContent) {
    return { kind: 'step', op: 'write' }
  }
  return { kind: 'conflict', reason: CONFLICT_CONTENT_DRIFT }
}

/** 由一条条目与本地状态构造可执行步骤。 */
export function buildStep(file, local, overwritten) {
  return {
    changeId: file.changeId,
    changeSetId: file.changeSetId,
    path: file.path,
    changeType: file.changeType,
    status: file.status,
    op: file.changeType === 'delete' ? 'delete' : 'write',
    content: file.changeType === 'delete' ? null : file.afterContent,
    previousContent: local.exists ? local.content : null,
    previousExisted: local.exists,
    overwritten: overwritten === true
  }
}
