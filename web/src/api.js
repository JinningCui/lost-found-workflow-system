const TOKEN_KEY = 'lost-found-token'

export const getToken = () => localStorage.getItem(TOKEN_KEY)
export const setToken = (token) => localStorage.setItem(TOKEN_KEY, token)
export const clearToken = () => localStorage.removeItem(TOKEN_KEY)

async function request(path, options = {}) {
  const headers = { ...options.headers }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  let response
  try {
    response = await fetch(`/api${path}`, { ...options, headers })
  } catch {
    throw new Error('无法连接后端服务。请确认系统已启动。')
  }

  const contentType = response.headers.get('content-type') || ''
  const raw = await response.text()
  let data = raw
  if (raw && contentType.includes('application/json')) {
    try { data = JSON.parse(raw) } catch { data = raw }
  }

  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login') clearToken()
    const message = typeof data === 'object' && data !== null
      ? (data.message || data.error || data.detail)
      : contentType.includes('text/plain') ? data.slice(0, 160) : null
    throw new Error(message || `请求失败（${response.status}）`)
  }
  return data
}

const post = (path, data) => request(path, { method: 'POST', body: JSON.stringify(data) })
const patch = (path, data) => request(path, { method: 'PATCH', body: JSON.stringify(data) })

function query(params) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== '' && value !== null && value !== undefined) search.set(key, value)
  }
  const suffix = search.toString()
  return suffix ? `?${suffix}` : ''
}

export const api = {
  login: (credentials) => post('/auth/login', credentials),
  me: () => request('/auth/me'),
  items: (filters = {}) => request(`/items${query(filters)}`),
  item: (id) => request(`/items/${encodeURIComponent(id)}`),
  createItem: (item) => post('/items', item),
  claims: (filters = {}) => request(`/claims${query(filters)}`),
  claim: (id) => request(`/claims/${encodeURIComponent(id)}`),
  createClaim: (claim) => post('/claims', claim),
  tasks: () => request('/tasks'),
  reviewEvidence: (claimId) => request(`/admin/claims/${encodeURIComponent(claimId)}/evidence`),
  completeTask: (id, decision) => post(`/tasks/${encodeURIComponent(id)}/complete`, decision),
  dispute: (data) => post('/disputes', data),
  disposal: (data) => post('/disposals', data),
  pickupCode: (claimId) => request(`/pickup/${encodeURIComponent(claimId)}/code`),
  redeemPickup: (claimId, code) => post(`/pickup/${encodeURIComponent(claimId)}/redeem`, { code }),
  services: () => request('/admin/services'),
  registerService: (serviceName) => post('/admin/services/register', { serviceName }),
  setServiceEnabled: (name, enabled) => patch(`/admin/services/${encodeURIComponent(name)}`, { enabled }),
  definitions: () => request('/admin/process-definitions'),
  deployDemoV2: () => post('/admin/process-definitions/demo-v2', {}),
  processes: () => request('/processes'),
  uploadDefinition: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/admin/process-definitions/upload', { method: 'POST', body: form })
  },
}
