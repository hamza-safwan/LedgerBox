import { describe, expect, it } from 'vitest'
import { money } from './client'
describe('money formatting',()=>{it('formats integer minor units without floating-point storage',()=>{expect(money(12345)).toBe('$123.45')})})
