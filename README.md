# Commerce Web

React and TypeScript storefront for the commerce POC. It supports registration,
sign-in, product browsing, basket management, address selection, checkout quotes,
credit-card token payment, and order status.

## Development

Run the BFF on port 8080, then start the Vite development server:

```bash
npm ci
npm run dev
```

Open `http://localhost:5173`. Requests under `/api` are proxied to the BFF. Set
`VITE_BFF_ORIGIN` when the BFF is available at a different development URL.

## Quality checks

```bash
npm run lint
npm test
npm run build
```

## Container

The production image builds the static application and serves it through Nginx.
Nginx handles SPA route fallback and proxies `/api` to `commerce-bff:8080` on the
Compose network.
