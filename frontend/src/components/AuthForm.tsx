import { ArrowRight, Eye, EyeOff, Lock, Mail, ShieldCheck, Ticket, Zap } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { login, register } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/useAuth'
import { EventPoster } from './EventPoster'

/** Shared by /login and /register. After success, returns to the page that sent you here. */
export function AuthForm({ mode }: { mode: 'login' | 'register' }) {
  const { logIn } = useAuth()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault() // stop the browser's own full-page form submit
    setBusy(true)
    setError(null)
    try {
      const res = mode === 'login' ? await login(email, password) : await register(email, password)
      logIn(res.token)
      toast.success(mode === 'login' ? 'Welcome back!' : 'Account created. Welcome to TixRush!')
      navigate(from, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, String(err)))
    } finally {
      setBusy(false)
    }
  }

  const isLogin = mode === 'login'
  return (
    <div className="container">
      <div className="auth">
        <EventPoster event={{ id: isLogin ? 7 : 11, name: isLogin ? 'Welcome back' : 'Join the rush' }} className="auth-art" showInitials={false}>
          <div className="auth-art-content">
            <span className="eyebrow"><Ticket size={14} /> TixRush</span>
            <h2>{isLogin ? 'Your next night out is waiting.' : 'Never miss the drop again.'}</h2>
            <ul>
              <li><Zap size={16} /> Instant seat reservation, even in a rush</li>
              <li><ShieldCheck size={16} /> Safe, simple checkout</li>
              <li><Ticket size={16} /> QR tickets in your pocket</li>
            </ul>
          </div>
        </EventPoster>

        <form className="auth-form" onSubmit={handleSubmit} noValidate>
          <h1>{isLogin ? 'Log in' : 'Create an account'}</h1>
          <p className="muted">{isLogin ? 'Good to see you again.' : 'It takes 10 seconds.'}</p>

          <label className="field">
            <span>Email</span>
            <div className="input-icon">
              <Mail size={18} />
              <input type="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            {error?.fieldErrors.email && <small className="field-error">{error.fieldErrors.email}</small>}
          </label>

          <label className="field">
            <span>Password</span>
            <div className="input-icon">
              <Lock size={18} />
              <input
                type={showPassword ? 'text' : 'password'}
                autoComplete={isLogin ? 'current-password' : 'new-password'}
                placeholder={isLogin ? 'Your password' : 'At least 8 characters'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
              <button type="button" className="input-action" onClick={() => setShowPassword((s) => !s)} aria-label={showPassword ? 'Hide password' : 'Show password'}>
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </div>
            {error?.fieldErrors.password && <small className="field-error">{error.fieldErrors.password}</small>}
          </label>

          {error && Object.keys(error.fieldErrors).length === 0 && (
            <div className="alert alert-error" role="alert">
              {error.status === 401 ? 'Wrong email or password.' : error.message}
            </div>
          )}

          <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy}>
            {busy ? 'Please wait…' : isLogin ? 'Log in' : 'Create account'} {!busy && <ArrowRight size={18} />}
          </button>

          <p className="muted center small">
            {isLogin ? (
              <>New to TixRush? <Link to="/register" state={{ from }}>Create an account</Link></>
            ) : (
              <>Already have an account? <Link to="/login" state={{ from }}>Log in</Link></>
            )}
          </p>
        </form>
      </div>
    </div>
  )
}
