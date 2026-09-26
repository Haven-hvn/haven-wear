# haven-wear

A standalone Wear OS music player for Haven-AOL gated audio. No phone, no account and no ICP wallet. The watch holds its own EVM key, unlocks gate versions v1, v3 and v4 against the canister by itself, and plays through Media3.

> Status: code only. It has **not** been compiled or run, and neither has any test. See [Unverified](#unverified).

## Setup

```sh
# local.properties (optional; every key has a public default)
haven.aol.canisterId=gny6k-fqaaa-aaaab-ag3ra-cai
haven.aol.icHost=https://ic0.app
arkiv.endpointUrl=https://rpc.tiramisu.db-chain.testnet.arkiv.network
evm.rpc.base=https://base-rpc.publicnode.com   # also .ethereum .arbitrum .optimism .sepolia
```

Only public configuration goes into `BuildConfig`. No key, seed or token is ever built into the app.

The key-unwrap native library comes from `app/src/main/rust/haven_vetkeys` through `tools/build-vetkeys-android.sh`, which runs before the JNI libraries are merged. It needs `cargo` and an NDK. Without them the build still succeeds, but sealed tracks report that the library is missing and won't unlock.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## How it works

| | |
|---|---|
| Wallet | On first launch, 16 bytes of `SecureRandom` entropy become a 12-word BIP-39 phrase, derived at `m/44'/60'/0'/0/0`. Only the entropy is stored, AES-GCM encrypted under a non-exportable Keystore key (StrongBox where available) in `noBackupFilesDir`. Backup and device transfer are disabled. |
| Signing | `WatchWallet` implements the same `WalletSession` interface the phone uses, so the ported `HavenAolImpl` runs unchanged. It signs silently, but only Haven gate requests (`HavenAOL` domain, gate primary types, its own address, the matching chain). Anything else is refused before the key is rebuilt. It never sends transactions, so it needs no ETH. |
| Library | Arkiv gate conditions ∩ `balanceOf` for the watch address over public RPC (works for ERC-20 and ERC-721), audio only. Each track is **open**, **needs more** ("Hold 25 FWB · this watch has 3") or **drip** (progress toward the market-cap target, read from `getMarketCap` with no signature). The last library is stored as JSON, so the app opens straight into your music. |
| Content | Decrypt once, keep the file, all inside foc-cache 0.2.0. On a gated track's first play (or when it's among the next three in the queue), foc streams the encrypted piece to disk and runs a `PieceTransform` that gets the key from the canister in one silent signed request (v3/v4 gates share one per epoch or drip stage) and decrypts it. foc keeps the decrypted file under the piece's CID; open tracks are kept as retrieved. From then on, restarts included, playback is a plain local file read (`FocCache.file`) with no network, no canister and no key. |
| Storage | Nobody manages it. foc lives in `filesDir` (never purged by the OS) with `AutoQuota`: half the free space plus what it already holds (0.5–16 GB), least recently played evicted first, no TTL. There's no downloads screen and no offline mode: whatever is on the watch plays, and anything else streams in on first play. |
| Playback | `MediaSessionService` + ExoPlayer, shuffle and repeat (off → all → one), queue, and volume on the crown. Wear's unsuitable-output rule is on, so music never plays through the watch speaker: it waits for headphones and opens the output switcher. It shows in the system media controls and supports "resume" after the process has been stopped. |
| Artwork | Taken from the audio file's embedded picture (ID3/FLAC/MP4) the first time the track is on the watch, and saved as a small JPEG in `filesDir/art` (pruned when foc evicts the track). The library shows covers for everything on the watch; other tracks get a stable generated cover. |
| Tile | "Continue listening": the last track, with a Resume button. |

## Screens

Setup is one screen, a QR code of the watch address with `0x1234…abcd` under it. After that: Now Playing ⇄ Library (a Horologist pager), Collections, Artists, Songs, Up next, and the Locked screen (a lock with the shortfall, or a drip progress ring with "Check again"). Settings holds the watch address, the recovery phrase and "Check for new music".

The recovery phrase sits behind the screen lock (`KeyguardManager`) on a `FLAG_SECURE` window and disappears as soon as you leave the screen. It is never copied to the clipboard, logged, synced or sent to the phone.

## Trade-offs

- **Recovery.** v1 has no restore-on-watch. To recover, type the 12 words into any Ethereum wallet. Losing the watch without having looked at the phrase means losing what the watch holds.
- **Decrypted music is on disk.** This is a deliberate choice for low-stakes content: one canister call per key, ever, instead of one after every restart. The files sit in app-private storage, which Wear OS encrypts at rest, with backup and device transfer disabled. Someone with root access to an unlocked watch could copy them. Content keys themselves are still never stored.
- **No pinning.** Eviction is least-recently-played by quota, so a song nobody has played for a long time on a full watch can be evicted. It streams and decrypts again the next time it's played.
- **JVM limits.** web3j holds the private key in a `BigInteger` and the phrase in a `String`, and neither can be wiped. Entropy and seed buffers are wiped, and every derivation lives only for one signature.

## Layout

`app/src/main/kotlin/haven/mobile/core/**` is copied from haven-mobile with the package names unchanged, so the two apps diff cleanly. The native library's JNI symbols also depend on those names. The copied modules are domain, crypto, arkiv, cache, haven-aol and the `WalletSession` interface. Local changes:

- `BuildConfig` references point at `haven.wear.BuildConfig`.
- The cache facade gains `file(ref, transform)`; foc lives in `filesDir` with `AutoQuota` and no TTL.
- The plaintext spool and PieceCID stub are removed.
- `GateAccessChecker` gains `holdings()` and `symbol()`.

Everything else is in `haven/wear/**`: `wallet`, `library`, `playback`, `tile`, `ui`.

## Unverified

- **Not compiled.** No Android SDK was used, and none of the Compose, Horologist, Media3 or protolayout API calls have been compiled.
- **foc-cache 0.2.0 is not published yet.** Publish it from `foc-local-first-android` (or `publishToMavenLocal` and add `mavenLocal()`) before building.
- **Unconfirmed versions:** `com.google.zxing:core:3.5.3`, `androidx.wear.tiles:tiles:1.5.0`, `androidx.wear.protolayout:protolayout:1.3.0` and `horologist-tiles:0.7.15` are pinned but weren't checked against a repository here. `web3j crypto 4.14.0` was confirmed in the local Gradle cache. The Wear, Compose and Media3 versions match compose-samples/Jetcaster.
- **web3j dependencies:** its EIP-4844 dependencies (`jc-kzg-4844`, tuweni) are excluded. The R8 rules assume nothing on the signing path reaches them.
- **Tests:** the new tests (wallet vectors and EIP-712 parity with `main.mo`, library model and codec, the library intersection with fakes) and the ported haven-mobile tests have never been run.
