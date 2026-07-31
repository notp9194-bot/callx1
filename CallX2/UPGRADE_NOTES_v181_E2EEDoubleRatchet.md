# v181 — 1:1 Chat Text: Real End-to-End Encryption (X3DH + Double Ratchet)

## Kya tha pehle
`core/.../utils/E2EEncryptionManager.java` file already codebase me thi, lekin
**kahin bhi use nahi ho rahi thi** (dead code) — aur jo protocol likha tha wo
weak tha: ek hi ECDH shared secret derive hota tha per partner, aur wahi
secret **hamesha** reuse hota tha har message ke liye. Isme:
- Forward secrecy nahi thi — agar wo ek shared key kabhi leak hui, to us
  partner ke saath ki **saari purani + future** messages decrypt ho sakti thi.
- Static prekey replay possible tha.
- Koi server-side prekey system nahi tha.

## Ab kya hai
`E2EEncryptionManager.java` ko **poora rewrite** kiya — ab ye WhatsApp/Signal
jis protocol-family (X3DH handshake + Double Ratchet) use karte hain, wahi
implement karta hai, EC P-256 + AES-256-GCM + HKDF/HMAC-SHA256 ke upar
(koi extra native crypto library add nahi karni padi — sab kuch JVM/Android
ke built-in `javax.crypto` se hi hua).

### Security properties
- **Per-message forward secrecy** — har message apni khud ki one-time key
  use karta hai, jo turant discard ho jaati hai. Aaj ki key leak hone se
  kal ke messages expose nahi hote.
- **Post-compromise security** — Diffie-Hellman ratchet step conversation ko
  next round-trip me "heal" kar deta hai, agar chain key kabhi compromise
  bhi ho jaaye.
- **One-time prekeys** — server-side atomic transaction se ek baar hi handout
  hoti hai, dobara kabhi nahi (classic static-prekey-replay weakness band).
- **Signed prekey authentication** — signed prekey ko identity key se ECDSA
  sign kiya jata hai; agar server bhi compromise ho jaaye aur prekey swap
  karne ki koshish kare, verification fail ho jaata hai.
- **Out-of-order tolerant** — skipped-message-key cache, flaky mobile network
  pe bhi decryption nahi tootta.
- **Ciphertext length padding** — plaintext ko 32-byte bucket tak pad kiya
  jaata hai encrypt karne se pehle, taaki message length se content ka andaza
  lagana mushkil ho.
- Saara private key material sirf Android Keystore-backed
  `EncryptedSharedPreferences` me rehta hai — kabhi upload nahi hota, kabhi
  log nahi hota.

## Kahan wire kiya
**Sirf 1:1 chat text messages** (`ChatActivity` / `ChatMessageSender`) —
group chat, media, aur forwarded messages is scope se bahar hain (jaisa
maanga gaya tha).

1. `ChatActivity.onCreate()` — `partnerUid` pata chalte hi
   `E2EEncryptionManager.ensureSession()` call hota hai background me
   (X3DH handshake shuru karta hai agar session already nahi hai).
2. `ChatActivity.doSendTextMessage()` — plaintext ko encrypt karke
   `Message.e2eWireText` (naya, `@Exclude` transient field) me daalta hai.
   **`Message.text` khud plaintext hi rehta hai** — taaki apna khud ka bubble
   aur local Room DB kabhi ciphertext na dikhaye.
3. `ChatMessageSender.firebasePushMessage()` — Firebase pe likhne ke *sirf us
   ek instant* ke liye `m.text` ko `m.e2eWireText` se swap karta hai, likhne
   ke turant baad wapas plaintext restore kar deta hai.
4. `ChatActivity`'s Firebase `ChildEventListener` (`onChildAdded`/
   `onChildChanged`) — naya `decryptIncomingIfNeeded()` helper incoming
   ciphertext ko Room me save hone se **pehle** decrypt kar deta hai, taaki
   niche ka sab kuch (bubble render, search, translate, export) hamesha
   plaintext hi dekhe, jaisa encryption se pehle tha.

   **Bugfix (isi patch me):** apna khud ka bheja hua message bhi isi listener
   se wapas "echo" hoke aata hai (chat reopen, reconnect, delta-sync waqt) —
   aur Firebase pe uske liye hamesha ciphertext hi pada hota hai. Pehle
   version me ye echo Room ki plaintext row ko ciphertext se **overwrite**
   kar deta tha — isi wajah se bhejte hi message "e2r1:{...}" jaisa raw JSON
   dikhne lagta tha. Fix: `E2EEncryptionManager.cacheOwnPlaintext()` /
   `takeOwnPlaintext()` — jab bhi hum khud koi encrypted message bhejte hain,
   uska plaintext message-id ke against ek encrypted local cache me save ho
   jaata hai; jab wahi message Firebase se echo hoke wapas aata hai, ciphertext
   ko is cache se restore kiye gaye plaintext se replace kar diya jaata hai
   (apna khud ka bheja hua ciphertext "decrypt" karne ki koshish nahi ki
   jaati — wo cryptographically possible hi nahi hai, kyunki wo hamare SEND
   chain se seal hua tha, jo reverse nahi chalti).

5. **Chat-list preview** — agar message encrypt hua, to `/chats` aur `/users`
   node me jo `lastMessage` likha jaata hai wo plaintext nahi, "🔒 Message"
   hota hai — warna encryption ka koi matlab nahi rehta agar preview text
   Firebase me plaintext padi rahe.

Agar encryption kisi bhi wajah se fail ho jaaye (session abhi ready nahi,
partner ne kabhi apni keys publish nahi ki), message **plaintext fallback**
ke saath chala jaata hai — message kabhi silently drop nahi hota (existing
offline-queue philosophy ke jaisa hi).

## Server changes (`index.js`)
Do naye endpoints add kiye, `e2e_prekeys/{uid}` Firebase path ke upar:

- `POST /e2e/keys` — apna prekey bundle (identity key, signed prekey +
  signature, one-time prekeys ka batch) upload/refresh karta hai.
- `GET /e2e/bundle/:uid` — kisi partner ka bundle fetch karta hai X3DH shuru
  karne ke liye, aur **atomically ek one-time prekey pop karta hai** (Firebase
  transaction) taaki wo dobara kabhi issue na ho.

Dono endpoints `Authorization: Bearer <Firebase ID token>` maangte hain
(naya `verifyFirebaseAuth` middleware) — is server ke baaki endpoints me
kahin bhi auth verification nahi thi, ye pehla hai.

### ⚠️ Firebase Security Rules — ye zaroor add karo
`e2e_prekeys` node ab **sirf server (Admin SDK) hi touch karta hai** — client
kabhi is node ko directly padhta/likhta nahi. Isliye rules me is node ko
completely lock kar do:

```json
{
  "rules": {
    "e2e_prekeys": {
      ".read": false,
      ".write": false
    }
  }
}
```

(Purana `e2e_keys/{uid}/publicKey` node ab unused hai — chaho to baad me
Firebase console se manually clean kar sakte ho, koi code usse ab reference
nahi karta.)

## Jo is scope me nahi kiya (aage kar sakte hain agar chahiye)
- Group chat encryption (sender-key protocol, alag design hota hai).
- Media messages (image/video/audio) encryption.
- UI: `ChatSecurityBottomSheet` me safety-number fingerprint dikhana
  (`getOurPublicKeyFingerprint()` / `getPartnerPublicKeyFingerprint()` methods
  already ready hain, bas ek row add karni hai UI me).
- Signed-prekey rotation ka automatic schedule (abhi ek baar generate hoke
  fixed rehta hai — rotate karne ka logic already hai `rotateSignedPreKey()`
  me, bas periodic trigger nahi laga).
- Server-side "prekeys khatam ho rahe hain, replenish karo" push notification
  (client already har 7 din me fresh batch upload karta hai regardless, to
  ye zaroori nahi hai abhi).

## Test kaise karo
1. Do alag accounts se login karo (2 devices/emulators).
2. Dono ek dusre se chat kholo (isse dono ki `ensureSession()` background me
   chal jayegi aur X3DH handshake ho jayega).
3. Text message bhejo — Firebase console me `messages/{chatId}/{msgId}/text`
   check karo: ab `"e2r1:{...}"` jaisa JSON dikhega, plaintext nahi.
4. Dono taraf message plaintext hi dikhna chahiye app ke andar (koi UI change
   nahi dikhega — encryption purely wire-level hai).
5. Chat list preview check karo — encrypted chat ke liye "🔒 Message" dikhna
   chahiye, actual text nahi.
