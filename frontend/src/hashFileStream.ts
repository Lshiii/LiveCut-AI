import { createSHA256 } from 'hash-wasm'

export async function hashFileChunks(file: Blob, progress: (bytes: number) => void, blockSize = 2_097_152) {
  const hash = await createSHA256(); hash.init()
  for (let offset = 0; offset < file.size; offset += blockSize) {
    const end = Math.min(file.size, offset + blockSize)
    hash.update(new Uint8Array(await file.slice(offset, end).arrayBuffer()))
    progress(end)
  }
  return hash.digest('hex')
}
