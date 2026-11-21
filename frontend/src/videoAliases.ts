import { shallowReactive } from 'vue'

const prefix = 'livecut-video-alias-v1:'
export const videoAliasLimit = 48

export function normalizeVideoAlias(value: string): string | null {
  const name = value.trim().replace(/\s+/g, ' ')
  return name.length <= videoAliasLimit && !/[\u0000-\u001f\u007f]/u.test(name) ? name : null
}

export function videoSearchName(original: string, alias?: string) {
  return alias ? `${alias} · ${original}` : original
}

/** One storage key per registered video so saving one note cannot discard another tab's edits. */
export function createVideoAliases(storage: () => Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>) {
  const names = shallowReactive(new Map<string, string>())
  const temporary = shallowReactive(new Set<string>())
  function read(id: string) {
    if (temporary.has(id)) return
    try {
      const raw = storage().getItem(prefix + id)
      names.set(id, raw === null ? '' : normalizeVideoAlias(raw) ?? '')
    } catch {
      if (!names.has(id)) names.set(id, '')
    }
  }
  function load(ids: string[]) {
    for (const id of ids) if (!names.has(id)) read(id)
  }
  function save(id: string, value: string) {
    const name = normalizeVideoAlias(value)
    if (!id || name === null) return false
    names.set(id, name)
    try {
      if (name) storage().setItem(prefix + id, name)
      else storage().removeItem(prefix + id)
      temporary.delete(id)
    } catch { temporary.add(id) }
    return true
  }
  function storageChanged(key: string | null) {
    if (key === null) { for (const id of names.keys()) read(id); return }
    if (key.startsWith(prefix) && names.has(key.slice(prefix.length))) read(key.slice(prefix.length))
  }
  return { names, temporary, load, save, storageChanged }
}
