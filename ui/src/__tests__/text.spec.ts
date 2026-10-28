import { describe, expect, it } from 'vitest'
import type { OriginCheck, Report } from '../api'
import { externalUrlText, guardCount, guardNotes, originAdvice } from '../text'

function report(overrides: Partial<Report>): Report {
  return {
    ok: true,
    mode: 'full',
    pushed: 0,
    unchanged: 0,
    removed: 0,
    failed: 0,
    planRequired: 0,
    heldRemovals: 0,
    guardTripped: false,
    originBlocked: false,
    wrongKind: false,
    startedAt: null,
    finishedAt: null,
    error: null,
    local: 0,
    remote: 0,
    skipped: 0,
    wouldRemove: 0,
    sourceKind: 'halo',
    origin: null,
    widgetEnabled: false,
    notes: [],
    ...overrides,
  }
}

const unset: OriginCheck = {
  ok: false,
  siteOrigin: null,
  sourceOrigin: 'https://blog.example.com',
  reason: 'Halo 的外部访问地址还没有设置',
}

describe('the clean-up guard reads the last full sync', () => {
  it('counts what the full sync would remove, or held back', () => {
    expect(guardCount(report({ wouldRemove: 12 }))).toBe(12)
    expect(guardCount(report({ heldRemovals: 3 }))).toBe(3)
    expect(guardCount(report({}))).toBe(0)
    expect(guardCount(null)).toBe(0)
    expect(guardCount(undefined)).toBe(0)
  })

  it('shows the notes of the full sync', () => {
    expect(guardNotes(report({ notes: ['类型不是 halo'] }))).toEqual(['类型不是 halo'])
    expect(guardNotes(null)).toEqual([])
  })
})

describe('an unset external address says what to set', () => {
  it('names the source address of MomoBird', () => {
    expect(originAdvice(unset)).toBe('请设成 https://blog.example.com')
    expect(externalUrlText(null, unset)).toBe('还没有设置，请设成 https://blog.example.com')
    expect(externalUrlText('', unset)).toBe('还没有设置，请设成 https://blog.example.com')
  })

  it('says nothing more when the address is set, or the source address is unknown', () => {
    expect(originAdvice({ ...unset, siteOrigin: 'https://www.example.com' })).toBeUndefined()
    expect(originAdvice({ ...unset, sourceOrigin: null })).toBeUndefined()
    expect(originAdvice(null)).toBeUndefined()
    expect(externalUrlText(null, null)).toBe('还没有设置')
    expect(externalUrlText('https://blog.example.com', unset)).toBe('https://blog.example.com')
  })
})
