# TixRush website

React 19 + TypeScript + Vite. Talks to the Spring Boot API in `../backend`.

```powershell
npm install
npm run dev      # http://localhost:5173  (start the backend on :8080 first)
npm test         # Vitest + React Testing Library
npm run build    # type-check + production build into dist/
npm run lint     # oxlint
```

| Folder | What's inside |
|---|---|
| `src/api/` | One function per backend endpoint, the `fetch` wrapper (`client.ts`), and TypeScript types mirroring the backend JSON |
| `src/auth/` | Where the JWT is kept (`tokenStore.ts`), the `useAuth()` hook, and route guards |
| `src/components/` | Reusable pieces: `BuyButton`, `Countdown`, `SeatsBar`, `EventCard`, `Header`… |
| `src/pages/` | One file per page (URL) |
| `src/lib/` | Small helpers: date formatting, `useNow()` |
