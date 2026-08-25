import { type FormEvent, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { isApiError } from '../api/client'

type AuthPageProps = {
  mode: 'login' | 'signup'
  submit: (credentials: { email: string; password: string }) => Promise<unknown>
}

export function AuthPage({ mode, submit }: AuthPageProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const loginMode = mode === 'login'

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await submit({ email, password })
      const from = (location.state as { from?: string } | null)?.from
      navigate(from ?? '/products', { replace: true })
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'Unable to continue. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="auth-layout">
      <div className="auth-copy">
        <p className="eyebrow">{loginMode ? 'Welcome back' : 'Start shopping'}</p>
        <h1>{loginMode ? 'Sign in to your basket' : 'Create your Bazaar account'}</h1>
        <p>{loginMode ? 'Your basket, saved addresses, and order journey are waiting.' : 'One account takes you from discovery to delivery.'}</p>
      </div>
      <form className="panel auth-form" onSubmit={onSubmit}>
        <h2>{loginMode ? 'Sign in' : 'Sign up'}</h2>
        {error && <div className="notice error" role="alert">{error}</div>}
        <label>Email<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required autoComplete="email" /></label>
        <label>Password<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} minLength={loginMode ? 1 : 8} required autoComplete={loginMode ? 'current-password' : 'new-password'} /></label>
        <button className="primary" type="submit" disabled={submitting}>{submitting ? 'Please wait…' : loginMode ? 'Sign in' : 'Create account'}</button>
        <p className="form-switch">{loginMode ? 'New to Bazaar?' : 'Already have an account?'} <Link to={loginMode ? '/signup' : '/login'}>{loginMode ? 'Create one' : 'Sign in'}</Link></p>
      </form>
    </section>
  )
}
