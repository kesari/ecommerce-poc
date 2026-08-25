import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/AppLayout'
import { ProtectedRoute } from './components/ProtectedRoute'
import { CheckoutProvider } from './context/CheckoutContext'
import { BasketPage } from './pages/BasketPage'
import { CheckoutPage } from './pages/CheckoutPage'
import { LoginPage } from './pages/LoginPage'
import { OrderStatusPage } from './pages/OrderStatusPage'
import { PaymentPage } from './pages/PaymentPage'
import { ProductsPage } from './pages/ProductsPage'
import { SignupPage } from './pages/SignupPage'
import './App.css'

function App() {
  return (
    <BrowserRouter>
      <CheckoutProvider>
        <AppLayout>
          <Routes>
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/products" element={<ProductsPage />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/basket" element={<BasketPage />} />
              <Route path="/checkout" element={<CheckoutPage />} />
              <Route path="/checkout/payment" element={<PaymentPage />} />
              <Route path="/orders/:orderId" element={<OrderStatusPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/products" replace />} />
          </Routes>
        </AppLayout>
      </CheckoutProvider>
    </BrowserRouter>
  )
}

export default App
