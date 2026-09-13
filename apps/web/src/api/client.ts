import type { components } from './schema'

export type Session = components['schemas']['Session']
export type Account = components['schemas']['Account']
export type Transfer = components['schemas']['Transfer']
export type Customer = components['schemas']['Customer']
export type ExternalAccount = components['schemas']['ExternalAccount']
export type AchTransfer = components['schemas']['AchTransfer']
export class ApiError extends Error { constructor(public status: number, public problem: { title?: string; detail?: string }) { super(problem.detail ?? 'Request failed') } }

const cookie = (name: string) => document.cookie.split('; ').find((part) => part.startsWith(`${name}=`))?.split('=')[1]
export async function ensureCsrf() { if (!cookie('XSRF-TOKEN')) await fetch('/api/v1/auth/csrf', { credentials: 'include' }) }
const noRefresh = new Set(['/api/v1/auth/register', '/api/v1/auth/verify-email', '/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/password-reset'])
async function request<T>(path: string, init: RequestInit, retry: boolean): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) await ensureCsrf()
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  const token = cookie('XSRF-TOKEN')
  if (token && !['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-XSRF-TOKEN', decodeURIComponent(token))
  const response = await fetch(path, { ...init, headers, credentials: 'include' })
  if (response.status === 401 && retry && !noRefresh.has(path)) {
    await request('/api/v1/auth/refresh', { method: 'POST' }, false)
    return request(path, init, false)
  }
  if (!response.ok) { let problem = {}; try { problem = await response.json() } catch { problem = { detail: response.statusText } }; throw new ApiError(response.status, problem) }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> { return request(path, init, true) }
export const money = (minor: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(minor / 100)
