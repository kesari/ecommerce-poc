import { login } from '../api/auth'
import { AuthPage } from './AuthPage'

export function LoginPage() {
  return <AuthPage mode="login" submit={login} />
}
