import { hashFileChunks } from './hashFileStream'

// File is structured-cloned, not loaded as one ArrayBuffer. Only one 2 MiB
// input buffer and the incremental hash state are kept by this worker.
self.onmessage = async (event: MessageEvent<File>) => {
  const file = event.data
  try {
    const hash = await hashFileChunks(file, bytes => self.postMessage({ bytes }))
    self.postMessage({ hash })
  } catch { self.postMessage({ error: 'FILE_HASH_FAILED' }) }
}
