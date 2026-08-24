import { signup } from '../api/auth'
import { AuthPage } from './AuthPage'

export function SignupPage() {
  return <AuthPage mode="signup" submit={signup} />
}
