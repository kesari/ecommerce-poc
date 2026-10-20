/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { Component, type ReactNode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ProductsPage } from '../pages/ProductsPage'

class RenderBoundary extends Component<{children:ReactNode},{failure:boolean}> {
 state={failure:false}
 static getDerivedStateFromError() { return {failure:true} }
 render() { return this.state.failure?<p data-testid="business-render-error">Currency cannot be displayed</p>:this.props.children }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
test('PROTECTED_PRODUCT_CURRENCY_DISPLAY', async () => {
 const input=readFileSync(process.env.WIRE_INPUT!,'utf8')
 const requests:string[]=[]
 vi.stubGlobal('fetch',vi.fn(async (url:string) => {
  requests.push(String(url));
  if(url!=='/api/v1/products?page=0&size=20') throw new Error(`Unexpected transport ${url}`)
  return new Response(input,{status:200,headers:{'Content-Type':'application/json'}})
 }))
 const {container}=render(<RenderBoundary><MemoryRouter><ProductsPage/></MemoryRouter></RenderBoundary>)
 await waitFor(() => expect(Boolean(container.querySelector('.product-card')) || Boolean(screen.queryByTestId('business-render-error'))).toBe(true))
 expect(Array.from(container.querySelectorAll('.product-action strong')).map(p=>p.textContent),'BUSINESS_PRODUCT_CURRENCY_PRICES').toEqual(['₹123.45','₹67.89'])
 expect(requests,'ACTUAL_PRODUCTS_API_CALL').toEqual(['/api/v1/products?page=0&size=20'])
})
