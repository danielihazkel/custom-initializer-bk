import { describe, expect, it } from 'vitest'
import { coerce, guessMapping, parseCsv, type ImportField } from './csv'

describe('parseCsv', () => {
  it('reads quoted cells with the delimiter, quotes and newlines in them', () => {
    expect(parseCsv('name,note\n"Smith, J","said ""hi""\nand left"\n')).toEqual([
      ['name', 'note'],
      ['Smith, J', 'said "hi"\nand left'],
    ])
  })

  it('finds the delimiter and drops a byte-order mark and blank lines', () => {
    expect(parseCsv('﻿a;b\r\n1;2\r\n\r\n')).toEqual([['a', 'b'], ['1', '2']])
  })
})

describe('guessMapping', () => {
  const fields: ImportField[] = [
    { name: 'dueOn', label: 'Due on', kind: 'date', required: false },
    { name: 'customerId', label: 'Customer', kind: 'relation', required: true, also: ['customer'], numeric: true },
  ]

  it('matches a header by name, label or another name, ignoring case and punctuation', () => {
    expect(guessMapping(['DUE_ON', 'customer', 'other'], fields)).toEqual(['dueOn', 'customerId', null])
  })
})

describe('coerce', () => {
  it('reads each kind, and says why a cell cannot be read', () => {
    expect(coerce({ name: 'n', label: 'N', kind: 'integer', required: false }, '42')).toEqual({ value: 42 })
    expect(coerce({ name: 'n', label: 'N', kind: 'integer', required: false }, '4.2')).toHaveProperty('error')
    expect(coerce({ name: 'b', label: 'B', kind: 'boolean', required: false }, 'yes')).toEqual({ value: true })
    expect(coerce({ name: 'd', label: 'D', kind: 'date', required: false }, '2026-01-15')).toEqual({ value: '2026-01-15' })
    expect(coerce({ name: 'd', label: 'D', kind: 'date', required: false }, '15/01/2026')).toHaveProperty('error')
    expect(coerce({ name: 's', label: 'S', kind: 'string', required: false }, '  ')).toEqual({ value: null })
  })

  it('drops the apostrophe the export puts before text that starts like a formula', () => {
    expect(coerce({ name: 's', label: 'S', kind: 'string', required: false }, "'=SUM(A1)")).toEqual({ value: '=SUM(A1)' })
  })
})
