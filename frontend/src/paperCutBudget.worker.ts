import { suggestPaperCutBudget } from './paperCutBudget'
import type { PaperCutSource } from './paperCut'

self.onmessage = (event: MessageEvent<{ source: PaperCutSource; removed: Set<string>; targetMs: number; goal: string }>) => {
  const { source, removed, targetMs, goal } = event.data
  self.postMessage(suggestPaperCutBudget(source, removed, targetMs, goal))
}
