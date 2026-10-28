import type { OriginCheck, Report } from './api'

// Small pieces of the MomoBird page's text, kept out of the component so they can be tested.

/**
 * How many posts the clean-up guard held back. Read from the last FULL sync: a later
 * incremental run or retry has no removals of its own and must not hide the count.
 */
export function guardCount(lastFull: Report | null | undefined): number {
  return lastFull?.wouldRemove || lastFull?.heldRemovals || 0
}

/** The notes of the last full sync (why the guard held the removals back). */
export function guardNotes(lastFull: Report | null | undefined): string[] {
  return lastFull?.notes ?? []
}

/** When Halo's external address is not set: what to set it to (the source's address in MomoBird). */
export function originAdvice(origin: OriginCheck | null | undefined): string | undefined {
  if (!origin || origin.siteOrigin || !origin.sourceOrigin) return undefined
  return `请设成 ${origin.sourceOrigin}`
}

/** Halo's external address as the page shows it. */
export function externalUrlText(
  externalUrl: string | null | undefined,
  origin: OriginCheck | null | undefined,
): string {
  if (externalUrl) return externalUrl
  const advice = originAdvice(origin)
  return advice ? `还没有设置，${advice}` : '还没有设置'
}
