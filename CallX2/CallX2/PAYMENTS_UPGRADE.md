# CallX Payments Upgrade

This upgrade adds a WhatsApp-style payment foundation without connecting a
real bank, UPI or payment gateway.

## Included flows

- Payments Home: balance placeholder, Send Money, Request Money, Scan QR,
  Transaction History, Bank Account / UPI and Payment Settings.
- Send Money and Request Money: contact/UPI, amount, note and confirmation.
- Confirmation: demo PIN/UPI PIN gate, success/failure receipt screen and
  share receipt action.
- QR scanner: camera scanner plus gallery QR entry point.
- Transaction History and Details: filters, amount, status, reference ID,
  timestamp, note and share receipt.
- Bank / UPI setup: masked account and default account placeholder.
- PIN setup/change: only a configured flag is stored; the actual PIN is never
  persisted by the demo implementation.
- Chat attach sheet: Payment opens Send Money, Request Money, Scan QR or the
  shared Payments Home.

## Architecture

`feature-payments` is a separate Android library module. Screens depend on
`PaymentRepository`, which persists local records through Room and delegates
gateway work to `PaymentService`.

`MockPaymentService` is the current implementation. It returns a clearly
labelled local demo response and never moves money. To connect UPI/bank/gateway
later, implement `PaymentService` for the provider and supply it from
`PaymentRepositoryImpl`; the screen layer does not need to change.

Room database version 47 adds:

- `payment_transactions`
- `payment_accounts`
- `payment_pins`

The 46 → 47 migration is included in `core/AppDatabase`.