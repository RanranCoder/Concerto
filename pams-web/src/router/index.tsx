import { createBrowserRouter, Navigate } from 'react-router-dom'
import { lazy } from 'react'
import type { ReactNode } from 'react'
import { useAuthStore } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout'

const Login = lazy(() => import('@/pages/Login'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))

export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <MainLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Dashboard /> },
    ],
  },
])
