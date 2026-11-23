import { useEffect, useState, type ChangeEvent } from 'react'
import { MAX_SIDE, shrinkImage } from './image'

/** What to do with a picture when the form is saved. */
export type PictureChange = { kind: 'keep' } | { kind: 'set'; blob: Blob } | { kind: 'remove' }

/**
 * The state behind a "Choose picture / Remove" field (event pictures and venue seating plans):
 * shrinks the chosen file in the browser, keeps a local preview, and remembers what to do on save.
 */
export function usePicture(currentUrl: string | null | undefined, maxSide = MAX_SIDE) {
  const [change, setChange] = useState<PictureChange>({ kind: 'keep' })
  const [newUrl, setNewUrl] = useState<string | null>(null) // a local preview of the new picture
  const [preparing, setPreparing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Free the preview's memory when it's replaced or the form closes.
  useEffect(() => () => { if (newUrl) URL.revokeObjectURL(newUrl) }, [newUrl])

  const previewUrl = change.kind === 'set' ? newUrl : change.kind === 'remove' ? null : currentUrl ?? null

  async function choose(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = '' // so choosing the same file again still triggers a change
    if (!file) return
    setError(null)
    setPreparing(true)
    try {
      const blob = await shrinkImage(file, maxSide)
      setChange({ kind: 'set', blob })
      setNewUrl(URL.createObjectURL(blob))
    } catch (err) {
      setError(err instanceof Error ? err.message : "We couldn't use that picture.")
    } finally {
      setPreparing(false)
    }
  }

  function remove() {
    setError(null)
    setNewUrl(null)
    setChange(currentUrl ? { kind: 'remove' } : { kind: 'keep' })
  }

  return { change, previewUrl, preparing, error, choose, remove }
}
