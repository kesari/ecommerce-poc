import type { ReactNode } from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { clearTokens } from '../auth/authStore'
import { useAuth } from '../auth/useAuth'

export function AppLayout({ children }: { children: ReactNode }) {
  const authenticated = useAuth()
  const navigate = useNavigate()

  function signOut() {
    clearTokens()
    navigate('/login')
  }

  return (
    <div className="app-shell">
      <header className="site-header">
        <Link className="brand" to="/products" aria-label="Bazaar home">
          <span className="brand-mark">B</span>
          <span>Bazaar</span>
        </Link>
        <nav aria-label="Main navigation">
          <NavLink to="/products">Products</NavLink>
          {authenticated && <NavLink to="/basket">Basket</NavLink>}
          {authenticated ? (
            <button className="link-button" type="button" onClick={signOut}>Sign out</button>
          ) : (
            <NavLink to="/login">Sign in</NavLink>
          )}
        </nav>
      </header>
      <main>{children}</main>
      <footer>Secure POC checkout · Credit card test tokens only</footer>
    </div>
  )
}
