import { type FormEvent, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { applyBasketCoupon, getBasket, removeBasketCoupon, removeBasketItem, updateBasketQuantity } from '../api/basket'
import { isApiError } from '../api/client'
import type { Basket } from '../api/types'
import { ErrorNotice } from '../components/ErrorNotice'
import { money } from '../utils/format'

export function BasketPage() {
  const location = useLocation()
  const [basket, setBasket] = useState<Basket | null>(null)
  const [coupon, setCoupon] = useState('')
  const [replacement, setReplacement] = useState<string | null>(null)
  const [error, setError] = useState<string | null>((location.state as { message?: string } | null)?.message ?? null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getBasket()
      .then(setBasket)
      .catch((caught: unknown) => setError(isApiError(caught) ? caught.message : 'Your basket could not be loaded.'))
      .finally(() => setLoading(false))
  }, [])

  async function changeQuantity(productId: string, quantity: number) {
    if (quantity < 1) {
      return
    }
    setError(null)
    try {
      setBasket(await updateBasketQuantity(productId, quantity))
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The quantity could not be changed.')
    }
  }

  async function remove(productId: string) {
    setError(null)
    try {
      setBasket(await removeBasketItem(productId))
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The item could not be removed.')
    }
  }

  async function applyCoupon(event: FormEvent) {
    event.preventDefault()
    const requestedCode = coupon.trim().toUpperCase()
    if (!requestedCode) {
      return
    }
    setError(null)
    setReplacement(null)
    try {
      setBasket(await applyBasketCoupon(requestedCode))
      setCoupon('')
    } catch (caught) {
      if (isApiError(caught) && caught.problem.code === 'COUPON_ALREADY_APPLIED') {
        setReplacement(requestedCode)
      } else {
        setError(isApiError(caught) ? caught.message : 'The coupon could not be applied.')
      }
    }
  }

  async function removeCoupon() {
    setError(null)
    try {
      setBasket(await removeBasketCoupon())
      setReplacement(null)
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The coupon could not be removed.')
    }
  }

  async function replaceCoupon() {
    if (!replacement) {
      return
    }
    setError(null)
    try {
      await removeBasketCoupon()
      setBasket(await applyBasketCoupon(replacement))
      setReplacement(null)
      setCoupon('')
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The coupon could not be replaced.')
    }
  }

  if (loading) {
    return <section className="page"><div className="loading">Loading your basket…</div></section>
  }

  return (
    <section className="page narrow-page">
      <div className="page-heading"><div><p className="eyebrow">Your selection</p><h1>Basket</h1></div></div>
      <ErrorNotice message={error} />
      {!basket || basket.items.length === 0 ? (
        <div className="empty-state"><span>0</span><h2>Your basket is empty</h2><p>Add something you love and it will appear here.</p><Link className="button primary" to="/products">Browse products</Link></div>
      ) : (
        <div className="basket-layout">
          <div className="panel basket-lines">
            {basket.items.map((item) => (
              <article className="basket-line" key={item.productId}>
                <div className="line-monogram">{item.name.slice(0, 1)}</div>
                <div className="line-copy"><h2>{item.name}</h2><p>{money(item.unitPriceMinor, item.currency)} each</p></div>
                <label className="quantity">Quantity<input aria-label={`Quantity for ${item.name}`} type="number" min="1" value={item.quantity} onChange={(event) => void changeQuantity(item.productId, Number(event.target.value))} /></label>
                <strong>{money(item.lineTotalMinor, item.currency)}</strong>
                <button className="danger-link" type="button" onClick={() => void remove(item.productId)}>Remove</button>
              </article>
            ))}
          </div>
          <aside className="panel basket-summary">
            <h2>Summary</h2>
            <dl className="price-breakdown">
              <div><dt>Subtotal</dt><dd>{money(basket.subtotalMinor, basket.currency)}</dd></div>
              <div><dt>Discount</dt><dd>−{money(basket.discountMinor, basket.currency)}</dd></div>
              <div className="total-row"><dt>Basket total</dt><dd>{money(basket.totalMinor, basket.currency)}</dd></div>
            </dl>
            {basket.couponCode ? <div className="coupon-active"><div><span>Coupon active</span><strong>{basket.couponCode}</strong></div><button type="button" onClick={() => void removeCoupon()}>Remove</button></div> : null}
            <form className="coupon-form" onSubmit={applyCoupon}><label>Coupon code<input value={coupon} onChange={(event) => setCoupon(event.target.value)} placeholder="SAVE10" /></label><button type="submit">Apply</button></form>
            {replacement && <div className="notice warning" role="alert"><p>{basket.couponCode ?? 'Another coupon'} is already active.</p><button type="button" onClick={() => void replaceCoupon()}>Replace with {replacement}</button></div>}
            <Link className="button primary full" to="/checkout">Continue to checkout</Link>
          </aside>
        </div>
      )}
    </section>
  )
}
