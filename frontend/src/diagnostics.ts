/** Public diagnostics contain only server-derived codes and bounded counters. */
export const categoryLabels = {
  MODEL_OUTPUT: '模型结果', BUDGET: '调用预算', PROVIDER: '模型服务', OCR_CACHE: '画面证据',
  EXECUTION: '任务执行', DEPENDENCY: '前序阶段', INPUT: '输入素材', INTEGRITY: '文件校验', STORAGE: '运行环境',
} as const
export const suggestionLabels = {
  CHECK_FORMAT_THEN_RETRY: '先检查结果格式和分类规则，再根据任务允许的操作重试。',
  NEW_JOB_WITH_BUDGET: '请新建预算足够的分析任务。重试仍使用本任务原预算，不会增加可调用次数。',
  CHECK_WINDOW_CONFIGURATION: '请检查视频时长和分析窗口配置，调整后新建分析任务。',
  RECONCILE_MODEL_CALL: '先恢复或核对已有模型调用的结果，确认调用状态后再处理，避免重复提交。',
  CHECK_PROVIDER_CONFIGURATION: '请检查模型服务的凭证、权限和模型配置，修正后再处理任务。',
  RESUME_PROVIDER_TASK: '请先恢复查询已有的转写任务，确认结果后再继续。',
  CHECK_PROVIDER_AND_CALL_STATE: '请检查模型服务是否可用，并核对已有调用状态后再处理。',
  WAIT_FOR_CACHE_OWNER: '其他任务正在准备画面证据，请等待后刷新状态。系统仅在执行次数上限内退避重试。',
  CHECK_CACHE_CAPACITY: '请先检查画面证据缓存的空间和容量上限，处理后再恢复任务。',
  CHECK_STORAGE_SAFETY: '请先检查存储目录和文件引用是否安全，再恢复任务。',
  INSPECT_CACHE_INTEGRITY: '请先排查画面证据是否损坏或冲突，确认完整性后再恢复任务。',
  VERIFY_SOURCE_AND_RUNTIME: '请核对原视频、抽帧工具和识别模型是否在执行期间发生变化。',
  CONFIGURE_OCR_RUNTIME: '请检查本地 OCR 依赖、模型文件和配置，准备完成后再恢复任务。',
  CHECK_WORKER_STORAGE: '请检查计算服务的工具、目录权限和磁盘空间，修正后再恢复任务。',
  CHECK_WORKER_THEN_RETRY: '请检查计算服务的运行状态和执行时限，再根据任务允许的操作重试。',
  RESOLVE_UPSTREAM_FAILURE: '请先处理前序阶段的失败，再恢复后续分析。',
  CREATE_NEW_JOB: '当前执行次数已耗尽，请检查原因后新建分析任务。',
  CHECK_SOURCE_AND_CONFIGURATION: '请检查视频、音频格式和分析配置，修正后再创建任务。',
  CHECK_ARTIFACT_INTEGRITY: '请检查来源文件和已有产物的完整性，确认后再恢复任务。',
  CHECK_EXECUTION_ENVIRONMENT: '请检查计算服务和本次执行记录，再根据任务允许的操作处理。',
} as const
type Category = keyof typeof categoryLabels
type Suggestion = keyof typeof suggestionLabels
interface Reason { title: string; category: Category; suggestion: Suggestion; codes?: readonly string[] }
const reason = (title: string, category: Category, suggestion: Suggestion, codes?: readonly string[]): Reason => ({ title, category, suggestion, codes })
const reasons: Record<string, Reason> = {
  MODEL_OUTPUT_INVALID: reason('分类结果未通过校验', 'MODEL_OUTPUT', 'CHECK_FORMAT_THEN_RETRY', ['INVALID_MODEL_OUTPUT']),
  ASSIGNMENT_KEYS_MISMATCH: reason('分类结果存在缺项或多项', 'MODEL_OUTPUT', 'CHECK_FORMAT_THEN_RETRY', ['INVALID_MODEL_OUTPUT']),
  CLASSIFICATION_VALUES_INVALID: reason('分类内容不符合约定', 'MODEL_OUTPUT', 'CHECK_FORMAT_THEN_RETRY', ['INVALID_MODEL_OUTPUT']),
  CLASSIFICATION_SCHEMA_INVALID: reason('分类结果格式不符合约定', 'MODEL_OUTPUT', 'CHECK_FORMAT_THEN_RETRY', ['INVALID_MODEL_OUTPUT']),
  DISCOVERY_SCHEMA_INVALID: reason('商品发现结果未通过校验', 'MODEL_OUTPUT', 'CHECK_FORMAT_THEN_RETRY', ['INVALID_DISCOVERY_OUTPUT']),
  REQUEST_BUDGET_EXHAUSTED: reason('模型请求预算不足', 'BUDGET', 'NEW_JOB_WITH_BUDGET', ['BUDGET_EXHAUSTED', 'MODEL_BUDGET_EXHAUSTED']),
  WINDOW_LIMIT_EXCEEDED: reason('分析窗口数量超过上限', 'BUDGET', 'CHECK_WINDOW_CONFIGURATION', ['BUDGET_EXHAUSTED']),
  PROVIDER_OUTCOME_UNKNOWN: reason('模型调用结果尚未确认', 'PROVIDER', 'RECONCILE_MODEL_CALL', ['UNKNOWN_OUTCOME', 'MODEL_CALL_REQUIRES_RECOVERY', 'INVALID_MODEL_RECOVERY_STATE', 'MODEL_RESPONSE_PERSISTENCE_FAILED', 'CALL_STATE_UNCERTAIN', 'MODEL_RESPONSE_ALREADY_EXISTS', 'MODEL_DISPATCH_NOT_GRANTED']),
  PROVIDER_CONFIGURATION_INVALID: reason('模型服务配置不可用', 'PROVIDER', 'CHECK_PROVIDER_CONFIGURATION', ['AUTHENTICATION_FAILED', 'PERMISSION_DENIED', 'MISSING_API_KEY', 'INVALID_PROVIDER_REQUEST', 'MODEL_CONFIG_CONFLICT', 'INVALID_CONFIGURATION']),
  PROVIDER_QUERY_INTERRUPTED: reason('模型任务查询未完成', 'PROVIDER', 'RESUME_PROVIDER_TASK', ['POLL_TIMEOUT', 'QUERY_TIMEOUT']),
  PROVIDER_REQUEST_FAILED: reason('模型服务请求失败', 'PROVIDER', 'CHECK_PROVIDER_AND_CALL_STATE', ['RATE_LIMITED', 'PROVIDER_UNAVAILABLE', 'PROVIDER_ERROR', 'NETWORK_ERROR', 'TASK_FAILED', 'SUBTASK_FAILED', 'INVALID_RESPONSE', 'INVALID_RESULT']),
  OCR_CACHE_BUSY: reason('画面证据正在由其他任务准备', 'OCR_CACHE', 'WAIT_FOR_CACHE_OWNER', ['OCR_CACHE_BUSY']),
  OCR_CACHE_CAPACITY_EXCEEDED: reason('画面证据缓存容量不足', 'OCR_CACHE', 'CHECK_CACHE_CAPACITY', ['OCR_CACHE_CAPACITY_EXCEEDED']),
  OCR_CACHE_UNSAFE_PATH: reason('画面证据存储引用不安全', 'OCR_CACHE', 'CHECK_STORAGE_SAFETY', ['OCR_CACHE_UNSAFE_PATH']),
  OCR_CACHE_CORRUPT: reason('画面证据完整性校验失败', 'OCR_CACHE', 'INSPECT_CACHE_INTEGRITY', ['OCR_CACHE_CORRUPT']),
  OCR_CACHE_OUTPUT_CONFLICT: reason('本次画面证据与已有文件冲突', 'OCR_CACHE', 'INSPECT_CACHE_INTEGRITY', ['OCR_CACHE_OUTPUT_CONFLICT']),
  OCR_CACHE_INPUT_CHANGED: reason('画面证据的输入或运行环境发生变化', 'OCR_CACHE', 'VERIFY_SOURCE_AND_RUNTIME', ['OCR_CACHE_INPUT_CHANGED']),
  OCR_CACHE_IDENTITY_REQUIRED: reason('画面识别配置缺少稳定标识', 'OCR_CACHE', 'CONFIGURE_OCR_RUNTIME', ['OCR_CACHE_IDENTITY_REQUIRED']),
  OCR_CACHE_RUNTIME_NOT_READY: reason('画面识别模型尚未准备好', 'OCR_CACHE', 'CONFIGURE_OCR_RUNTIME', ['OCR_CACHE_RUNTIME_NOT_READY']),
  OCR_CACHE_UNSUPPORTED_RUNTIME: reason('当前画面识别配置不受支持', 'OCR_CACHE', 'CONFIGURE_OCR_RUNTIME', ['OCR_CACHE_UNSUPPORTED_RUNTIME']),
  OCR_CACHE_STORAGE_ERROR: reason('画面证据存储不可用', 'OCR_CACHE', 'CHECK_WORKER_STORAGE', ['OCR_CACHE_STORAGE_ERROR']),
  EXECUTION_TIMEOUT: reason('本次执行已超时或失去执行权', 'EXECUTION', 'CHECK_WORKER_THEN_RETRY', ['LEASE_EXPIRED', 'LEASE_LOST', 'HARD_DEADLINE_EXCEEDED', 'PROCESS_TIMEOUT', 'FRAME_TIMEOUT']),
  UPSTREAM_STAGE_FAILED: reason('前序阶段失败，后续阶段已停止', 'DEPENDENCY', 'RESOLVE_UPSTREAM_FAILURE', ['PARENT_STAGE_FAILED']),
  ATTEMPTS_EXHAUSTED: reason('执行次数已达上限', 'EXECUTION', 'CREATE_NEW_JOB', ['ATTEMPTS_EXHAUSTED']),
  INPUT_INVALID: reason('输入素材或配置不符合要求', 'INPUT', 'CHECK_SOURCE_AND_CONFIGURATION', ['INVALID_MEDIA', 'UNSUPPORTED_MEDIA', 'UNSUPPORTED_TIMELINE', 'INVALID_AUDIO', 'INVALID_SOURCE', 'INVALID_INPUT']),
  ARTIFACT_INVALID: reason('来源文件或已有产物未通过校验', 'INTEGRITY', 'CHECK_ARTIFACT_INTEGRITY', ['ARTIFACT_HASH_MISMATCH', 'ARTIFACT_INPUT_MISMATCH', 'SOURCE_HASH_MISMATCH', 'MISSING_ARTIFACT', 'INVALID_CHECKPOINT', 'MODEL_RESPONSE_HASH_MISMATCH', 'INVALID_MODEL_RESPONSE_RECORDING', 'INVALID_RESULT_MANIFEST']),
  STORAGE_UNAVAILABLE: reason('计算服务的工具或存储不可用', 'STORAGE', 'CHECK_WORKER_STORAGE', ['STORAGE_ERROR', 'OUTPUT_WRITE_FAILED', 'MODEL_RESPONSE_STORAGE_UNAVAILABLE', 'TOOL_NOT_FOUND']),
  UNCLASSIFIED_FAILURE: reason('本次执行失败', 'EXECUTION', 'CHECK_EXECUTION_ENVIRONMENT'),
}
const countFields = ['missing_core_id_count', 'extra_context_id_count', 'unknown_key_count', 'non_core_key_count', 'value_error_count'] as const
type CountField = typeof countFields[number]
type Repair = 'NOT_ATTEMPTED' | 'FAILED_VALIDATION' | 'INTERRUPTED'
export interface StageDiagnostic extends Partial<Record<CountField, number>> {
  schema_version: 1; attempt: number; error_code: string; category: Category; reason_code: string; suggestion_code: Suggestion
  window_index?: number; window_count?: number; format_repair?: Repair
}
const fields = new Set(['schema_version', 'attempt', 'error_code', 'category', 'reason_code', 'suggestion_code', 'window_index', 'window_count', 'format_repair', ...countFields])
const repairs: Record<Repair, string> = { NOT_ATTEMPTED: '未进行格式修复', FAILED_VALIDATION: '格式修复后仍未通过校验', INTERRUPTED: '格式修复未完成' }
const integer = (value: unknown, min: number, max: number): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= min && value <= max
export function safeErrorCode(value: unknown): string | undefined { return typeof value === 'string' && /^[A-Z0-9_]{1,100}$/.test(value) ? value : undefined }

/** Ignore an unsupported optional diagnostic, while retaining the authoritative task state. */
export function parseDiagnostic(raw: unknown, attempt: number, errorCode: string | null): StageDiagnostic | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || !safeErrorCode(errorCode) || errorCode!.length > 64) return undefined
  const value = raw as Record<string, unknown>
  if (Object.keys(value).some(field => !fields.has(field)) || value.schema_version !== 1
      || !integer(value.attempt, 0, Number.MAX_SAFE_INTEGER) || value.attempt !== attempt || value.error_code !== errorCode
      || typeof value.reason_code !== 'string') return undefined
  const policy = Object.hasOwn(reasons, value.reason_code) ? reasons[value.reason_code] : undefined
  if (!policy || value.category !== policy.category || value.suggestion_code !== policy.suggestion
      || (policy.codes && !policy.codes.includes(errorCode!))) return undefined
  const result: StageDiagnostic = { schema_version: 1, attempt, error_code: errorCode!, category: policy.category, reason_code: value.reason_code, suggestion_code: policy.suggestion }
  if ((value.window_index === undefined) !== (value.window_count === undefined)) return undefined
  if (value.window_index !== undefined) {
    if (!integer(value.window_index, 0, 9999) || !integer(value.window_count, 1, 10000) || value.window_index >= value.window_count) return undefined
    result.window_index = value.window_index; result.window_count = value.window_count
  }
  for (const field of countFields) {
    if (value[field] !== undefined) {
      if (!integer(value[field], 0, 1000000)) return undefined
      result[field] = value[field]
    }
  }
  if (result.non_core_key_count !== undefined && result.extra_context_id_count !== undefined && result.unknown_key_count !== undefined
      && result.non_core_key_count !== result.extra_context_id_count + result.unknown_key_count) return undefined
  if (value.format_repair !== undefined) {
    if (typeof value.format_repair !== 'string' || !Object.hasOwn(repairs, value.format_repair)
        || (value.format_repair === 'FAILED_VALIDATION' && errorCode !== 'INVALID_MODEL_OUTPUT')) return undefined
    result.format_repair = value.format_repair as Repair
  }
  return result
}

export function diagnosticView(raw: unknown, attempt: number, errorCode: string | null) {
  const diagnostic = parseDiagnostic(raw, attempt, errorCode)
  if (!diagnostic) return undefined
  const details: string[] = []
  if (diagnostic.window_index !== undefined && diagnostic.window_count !== undefined) details.push(`第 ${diagnostic.window_index + 1} / ${diagnostic.window_count} 个分析窗口`)
  const labels: Partial<Record<CountField, string>> = { missing_core_id_count: '缺少语句', extra_context_id_count: '多余上下文', unknown_key_count: '未知条目', value_error_count: '分类内容错误' }
  for (const field of countFields) if (labels[field] && diagnostic[field] !== undefined) details.push(`${labels[field]} ${diagnostic[field]} 项`)
  if (diagnostic.non_core_key_count !== undefined && diagnostic.extra_context_id_count === undefined && diagnostic.unknown_key_count === undefined) details.push(`多余条目 ${diagnostic.non_core_key_count} 项`)
  if (diagnostic.format_repair) details.push(repairs[diagnostic.format_repair])
  return { category: categoryLabels[diagnostic.category], title: reasons[diagnostic.reason_code]!.title, suggestion: suggestionLabels[diagnostic.suggestion_code], details }
}
