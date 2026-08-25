import { type FormEvent, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getBasket } from '../api/basket'
import { createAddress, createQuote, listAddresses } from '../api/checkout'
import { isApiError } from '../api/client'
import type { Address, AddressInput, Basket, Quote } from '../api/types'
import { ErrorNotice } from '../components/ErrorNotice'
import { PriceBreakdown } from '../components/PriceBreakdown'
import { useCheckout } from '../context/useCheckout'
import { countdown, deliveryDate } from '../utils/format'

const emptyAddress: AddressInput = {
  fullName: '',
  line1: '',
  line2: '',
  city: '',
  state: '',
  postalCode: '',
  country: 'IN',
  phoneNumber: '',
}

function QuotePreview({ quote, regenerate }: { quote: Quote; regenerate: () => void }) {
  const navigate = useNavigate()
  const [now, setNow] = useState(() => Date.now())
  const expired = new Date(quote.expiresAt).getTime() <= now

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  return (
    <section className="panel quote-preview" aria-label="Checkout quote">
      <div className="quote-header"><div><p className="eyebrow">Checkout preview</p><h2>Your estimated total</h2></div><span className={expired ? 'expiry expired' : 'expiry'}>{expired ? 'Expired' : `Expires in ${countdown(quote.expiresAt, now)}`}</span></div>
      <PriceBreakdown quote={quote} />
      <div className="delivery-window"><span>Estimated delivery</span><strong>{deliveryDate(quote.estimatedDelivery.from)} – {deliveryDate(quote.estimatedDelivery.to)}</strong></div>
      {expired ? <button className="primary full" type="button" onClick={regenerate}>Regenerate quote</button> : <button className="primary full" type="button" onClick={() => navigate('/checkout/payment')}>Continue to payment</button>}
    </section>
  )
}

export function CheckoutPage() {
  const { quote, setQuote } = useCheckout()
  const [addresses, setAddresses] = useState<Address[]>([])
  const [basket, setBasket] = useState<Basket | null>(null)
  const [selectedAddress, setSelectedAddress] = useState('')
  const [addingAddress, setAddingAddress] = useState(false)
  const [addressInput, setAddressInput] = useState<AddressInput>(emptyAddress)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [quoting, setQuoting] = useState(false)

  useEffect(() => {
    Promise.all([listAddresses(), getBasket()])
      .then(([savedAddresses, currentBasket]) => {
        setAddresses(savedAddresses)
        setBasket(currentBasket)
        setSelectedAddress(savedAddresses[0]?.id ?? '')
        setAddingAddress(savedAddresses.length === 0)
      })
      .catch((caught: unknown) => setError(isApiError(caught) ? caught.message : 'Checkout information could not be loaded.'))
      .finally(() => setLoading(false))
  }, [])

  function updateAddress(field: keyof AddressInput, value: string) {
    setAddressInput((current) => ({ ...current, [field]: value }))
  }

  async function saveAddress(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      const created = await createAddress(addressInput)
      setAddresses((current) => [...current, created])
      setSelectedAddress(created.id)
      setAddingAddress(false)
      setAddressInput(emptyAddress)
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The address could not be saved.')
    }
  }

  async function generateQuote() {
    if (!basket || !selectedAddress) {
      setError('Select a delivery address before generating a quote.')
      return
    }
    setQuoting(true)
    setError(null)
    try {
      setQuote(await createQuote(basket.basketId, selectedAddress))
    } catch (caught) {
      setError(isApiError(caught) ? caught.message : 'The checkout quote could not be generated.')
    } finally {
      setQuoting(false)
    }
  }

  if (loading) {
    return <section className="page"><div className="loading">Preparing checkout…</div></section>
  }

  return (
    <section className="page narrow-page">
      <div className="page-heading"><div><p className="eyebrow">Delivery and price</p><h1>Checkout</h1></div></div>
      <ErrorNotice message={error} />
      <div className="checkout-layout">
        <div className="checkout-addresses">
          <section className="panel">
            <div className="section-heading"><div><span className="step-number">1</span><h2>Delivery address</h2></div><button type="button" onClick={() => setAddingAddress((value) => !value)}>{addingAddress ? 'Choose saved' : 'Add new'}</button></div>
            {!addingAddress && addresses.length > 0 && <div className="address-list">{addresses.map((address) => <label className={selectedAddress === address.id ? 'address-card selected' : 'address-card'} key={address.id}><input type="radio" name="address" value={address.id} checked={selectedAddress === address.id} onChange={() => setSelectedAddress(address.id)} /><span><strong>{address.fullName}</strong><span>{address.line1}{address.line2 ? `, ${address.line2}` : ''}</span><span>{address.city}, {address.state} {address.postalCode}</span><span>{address.phoneNumber}</span></span></label>)}</div>}
            {addingAddress && <form className="address-form" onSubmit={saveAddress}>
              <label>Full name<input value={addressInput.fullName} onChange={(event) => updateAddress('fullName', event.target.value)} required /></label>
              <label className="wide">Address line 1<input value={addressInput.line1} onChange={(event) => updateAddress('line1', event.target.value)} required /></label>
              <label className="wide">Address line 2<input value={addressInput.line2 ?? ''} onChange={(event) => updateAddress('line2', event.target.value)} /></label>
              <label>City<input value={addressInput.city} onChange={(event) => updateAddress('city', event.target.value)} required /></label>
              <label>State<input value={addressInput.state} onChange={(event) => updateAddress('state', event.target.value)} required /></label>
              <label>Postal code<input value={addressInput.postalCode} onChange={(event) => updateAddress('postalCode', event.target.value)} required /></label>
              <label>Country<input value={addressInput.country} onChange={(event) => updateAddress('country', event.target.value)} pattern="[A-Za-z]{2}" required /></label>
              <label className="wide">Phone number<input value={addressInput.phoneNumber} onChange={(event) => updateAddress('phoneNumber', event.target.value)} required /></label>
              <button className="primary" type="submit">Save and use address</button>
            </form>}
          </section>
          {!addingAddress && <button className="primary quote-button" type="button" onClick={() => void generateQuote()} disabled={quoting || !selectedAddress}>{quoting ? 'Generating…' : quote ? 'Regenerate checkout quote' : 'Generate checkout quote'}</button>}
        </div>
        {quote ? <QuotePreview quote={quote} regenerate={() => void generateQuote()} /> : <aside className="panel quote-placeholder"><span className="step-number">2</span><h2>Price and delivery estimate</h2><p>Select an address and generate a quote to see shipping, tax, total, and the delivery window.</p></aside>}
      </div>
    </section>
  )
}
