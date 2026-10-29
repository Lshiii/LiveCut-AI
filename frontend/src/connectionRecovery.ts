export function isConnectionErrorCode(code: string): boolean {
  return code === 'NETWORK_UNAVAILABLE' || code === 'REQUEST_TIMEOUT'
}

interface DetailTarget { id: string; selectionVersion: number; attempts: number }

/** Retry reads only after a successful refresh, while the same selection is still wanted. */
export class DetailRecovery {
  private target: DetailTarget | null = null

  queue(id: string, selectionVersion: number) {
    this.target = { id, selectionVersion, attempts: 0 }
  }

  cancel() { this.target = null }

  next(selectionVersion: number, blocked: boolean): string | null {
    const target = this.target
    if (!target) return null
    if (target.selectionVersion !== selectionVersion || target.attempts >= 3) {
      this.cancel(); return null
    }
    if (blocked) return null
    target.attempts++
    return target.id
  }
}
