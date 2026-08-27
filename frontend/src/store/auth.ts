import { create } from 'zustand'
import { getToken, setToken, clearToken } from '@/utils/auth'
import type { UserProfile } from '@/types'

interface AuthState {
  token: string | null
  user: UserProfile | null
  permissions: string[]
  setAuth: (token: string, user: UserProfile, permissions: string[]) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: getToken(),
  user: null,
  permissions: [],
  setAuth: (token, user, permissions) => {
    setToken(token)
    set({ token, user, permissions })
  },
  logout: () => {
    clearToken()
    set({ token: null, user: null, permissions: [] })
  },
}))
