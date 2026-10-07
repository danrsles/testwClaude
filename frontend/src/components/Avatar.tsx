import { useState } from 'react'
import placeholder from '../assets/avatar-placeholder.svg'

interface Props {
  /** Image URL, or null for the placeholder. */
  src: string | null
  alt: string
  /** Width and height in pixels. */
  size: number
}

/**
 * A round profile picture. Shows a local placeholder when there is no URL, and
 * falls back to it if the URL fails to load, so a broken link never shows a
 * broken-image icon.
 */
export default function Avatar({ src, alt, size }: Props) {
  // Remember which URL failed rather than a boolean, so a new src gets a fresh try.
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  const usable = src && src !== failedSrc ? src : placeholder

  return (
    <img
      src={usable}
      alt={alt}
      width={size}
      height={size}
      onError={() => setFailedSrc(src)}
      className="shrink-0 rounded-full border border-stone-200 object-cover dark:border-stone-700"
      style={{ width: size, height: size }}
    />
  )
}
