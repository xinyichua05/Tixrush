/** Types the admin may pick. The backend checks the real file bytes again, so this is just a friendly first filter. */
export const PICTURE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_INPUT_BYTES = 15 * 1024 * 1024
/** Event pictures. Seating plans pass a bigger size, so the zone names on them stay readable. */
export const MAX_SIDE = 1600

/**
 * Shrinks a picture in the browser before uploading it: at most 1600px on the long side,
 * saved as WebP at 85% quality (or JPEG if this browser can't make WebP). A 6 MB phone photo
 * becomes ~200 KB, so uploads are fast and the database stays small. Redrawing it on a canvas
 * also drops hidden photo data such as the GPS location.
 */
export async function shrinkImage(file: File, maxSide = MAX_SIDE): Promise<Blob> {
  if (!PICTURE_TYPES.includes(file.type)) throw new Error('Please choose a JPG, PNG or WebP picture.')
  if (file.size > MAX_INPUT_BYTES) throw new Error('That picture is too big. Please choose one under 15 MB.')

  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    throw new Error("We couldn't open that picture. Please try another one.")
  }
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bitmap.width * scale)
  canvas.height = Math.round(bitmap.height * scale)
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  bitmap.close()

  const toBlob = (type: string) => new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, 0.85))
  // Browsers that can't encode WebP silently return a PNG instead, so check what we actually got.
  const webp = await toBlob('image/webp')
  if (webp && webp.type === 'image/webp') return webp
  const jpeg = await toBlob('image/jpeg')
  if (!jpeg) throw new Error("We couldn't prepare that picture. Please try another one.")
  return jpeg
}
