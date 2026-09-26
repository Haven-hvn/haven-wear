package haven.mobile.core.haven.aol

import haven.mobile.core.crypto.AesKeyCache
import haven.mobile.core.crypto.Keccak256
import haven.mobile.core.domain.MediaItem
import haven.mobile.core.domain.error.HavenError
import haven.mobile.core.wallet.WalletSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class HavenAolImpl @Inject constructor(
    private val config: HavenAolConfig,
    private val walletSession: WalletSession,
    private val aesKeyCache: AesKeyCache,
    private val nonceManager: NonceManager,
    private val gateRequestBuilder: GateRequestBuilder,
    private val vetKdUnwrap: haven.mobile.core.haven.aol.vetkeys.VetKdUnwrap,
    /**
     * One pooled client for every IC call this singleton makes. A fresh client per
     * request re-pays TLS to `ic0.app` on each unlock; the pool amortises it.
     * Bound in [HavenAolDiModule]; the default keeps test subclasses compiling.
     */
    private val icHttp: OkHttpClient = defaultIcHttpClient(),
) : HavenAol {

    override val canisterId: String get() = config.canisterId

    /**
     * Serializes every wallet signature this singleton requests.
     *
     * The wallet stack serves one request pipeline behind a single global
     * delegate: two overlapping `eth_signTypedData_v4` calls corrupt each
     * other and kill the process, which is exactly what a bulk unlock of
     * items on different gates used to do (each gate group signed in its own
     * coroutine). Gates sharing one batch still cost one signature; gates on
     * different groups queue behind it. Canister calls and native unwraps
     * stay parallel — only the wallet request is exclusive.
     */
    private val signMutex = Mutex()

    internal suspend fun signSerialized(
        session: WalletSession,
        json: String,
        chainId: Long,
    ): Result<String> = signMutex.withLock {
        session.signTypedDataV4(json, chainId)
    }

    override suspend fun decrypt(item: MediaItem, session: WalletSession): Result<ByteArray> {
        if (config.canisterId.isBlank() || config.icHost.isBlank()) {
            // Rendered directly by the viewer's error state, so it says what the reader can conclude
            // — not which build property is missing.
            return Result.failure(
                HavenError.CanisterCallFailed("This build of Haven can't unlock content."),
            )
        }
        val address = session.address.value ?: return Result.failure(HavenError.WalletNotConnected("No wallet connected"))
        // Check in-memory AES key cache first (FR-ACL-2) — gate key survives for session until disconnect
        val cacheKey = cacheKeyFor(item)
        aesKeyCache.get(cacheKey)?.let { return Result.success(it) }
        val gate = item.gate
        // Pre-seal V4 shapes (`wrappedKey` + `marketCapTarget`, no `encryptedAesKey`): no writer
        // emits them and they carry nothing the canister can unwrap. Real v4 drips are sealed
        // records (`Sealed.version == 4`) routed below. Fail closed, never derive for them.
        val isLegacyV4 = item.encryptionMetadata is haven.mobile.core.domain.GateMetadata.V4
        if (isLegacyV4) {
            return Result.failure(
                HavenError.UnsupportedGateMetadata(
                    "This premiere uses a legacy drip gate shape this build can't unlock.",
                ),
            )
        }
        // Sealed content routes on its record version: v1 unwraps below, anything else fails
        // closed (v3/v4 need their own canister methods). Only the content layer gates
        // playback — the CID layer seals a locator mobile never needs.
        val sealed = item.encryptionMetadata as? haven.mobile.core.domain.GateMetadata.Sealed
        if (sealed != null) {
            if (sealed.gateTypeConflict) {
                // The entity's gate_type attribute and its gate JSON disagree on the protocol:
                // a malformed or tampered record. Never guess which one to trust.
                timber.log.Timber.w(
                    "gate_type %s contradicts gate version %s for %s", sealed.attributeGateType, sealed.version, item.id,
                )
                return Result.failure(
                    HavenError.UnsupportedGateMetadata(
                        "This item's gate record is inconsistent, so Haven won't try to unlock it.",
                    ),
                )
            }
            return when (sealed.version) {
                1L -> decryptSealedV1(item, sealed, session, address, cacheKey)
                3L, 4L -> decryptSealedEpoch(item, sealed, session, address, cacheKey)
                else -> {
                    val label = if (sealed.version > 0) " v${sealed.version}" else ""
                    Result.failure(
                        HavenError.UnsupportedGateMetadata(
                            "This item is sealed with Haven-AOL$label — this build unwraps v1, v3 and v4 seals only.",
                        ),
                    )
                }
            }
        }
        val legacyGate = gate ?: return Result.failure(HavenError.CanisterCallFailed("No gate for ${item.id}"))
        return decryptLegacy(item, legacyGate, session, address, cacheKey)
    }

    /**
     * VetKD v1 unlock — dapp parity with `decryptContentKey`: transport keypair, canonical
     * EIP-712 signature, `requestDecryptionKey`, then the device unwrap of the bundled
     * `encrypted_key` against the record's sealed AES key.
     *
     * The record's own gate fields bind the derivation and the request (never the attribute
     * gate, which can disagree) — and anything incomplete fails closed before any signing
     * prompt, so the wallet never signs for an unlock that cannot complete.
     */
    private suspend fun decryptSealedV1(
        item: MediaItem,
        sealed: haven.mobile.core.domain.GateMetadata.Sealed,
        session: WalletSession,
        address: String,
        cacheKey: String,
    ): Result<ByteArray> {
        if (!vetKdUnwrap.isAvailable()) {
            return Result.failure(
                HavenError.Internal("Sealed unlock needs the native vetkeys library, which is missing from this build."),
            )
        }
        if (sealed.cid.isBlank() || sealed.chain.isBlank() || sealed.tokenAddress.isBlank()) {
            return Result.failure(
                HavenError.UnsupportedGateMetadata("This item's seal record is incomplete — this build can't unwrap it."),
            )
        }
        val gateChain = haven.mobile.core.domain.HavenChain.parse(sealed.chain)
            ?: return Result.failure(
                HavenError.UnsupportedGateMetadata("This item is gated on a network Haven can't check."),
            )
        val chainVariant = gateChain.aolVariant
        // The signature must commit to the chain actually checked: Sepolia gates sign
        // a Sepolia domain over a Sepolia wallet request (v3 already does this).
        val domainChainId = gateChain.chainId
        val thresholdNorm = normalizeSealedThreshold(sealed.threshold)
        val transport = vetKdUnwrap.generateTransportKeypair().getOrElse {
            timber.log.Timber.w(it, "VetKD transport keypair failed")
            return Result.failure(HavenError.Internal("Could not prepare the sealed unlock."))
        }
        val nonce = nonceManager.getNonce(address, config.canisterId)
        val typedData = gateRequestBuilder.buildV1Request(
            evmAddress = address,
            transportPublicKeyHex = "0x" + transport.publicKey.toHex(),
            nonceDecimal = nonce,
            domainChainId = domainChainId,
        )
        val sig = signSerialized(session, typedData, domainChainId).getOrElse {
            return Result.failure(HavenError.CanisterCallFailed("Signing failed: ${it.message}"))
        }
        val sigBytes = parseWalletSignature(sig) ?: return Result.failure(
            HavenError.InvalidSignatureFormat("The wallet returned an unusable signature."),
        )
        val nonceNat = try {
            java.math.BigInteger(nonce)
        } catch (_: Exception) {
            return Result.failure(HavenError.Internal("Could not prepare the sealed unlock."))
        }
        val record = dev.ic.kotlin.candid.CandidValue.CandidRecord(
            mapOf(
                dev.ic.kotlin.candid.fieldId("chain") to dev.ic.kotlin.candid.CandidValue.CandidVariant(
                    dev.ic.kotlin.candid.fieldId(chainVariant), dev.ic.kotlin.candid.CandidValue.CandidNull,
                ),
                dev.ic.kotlin.candid.fieldId("tokenAddress") to dev.ic.kotlin.candid.CandidValue.CandidText(sealed.tokenAddress),
                dev.ic.kotlin.candid.fieldId("threshold") to dev.ic.kotlin.candid.CandidValue.CandidNat(java.math.BigInteger(thresholdNorm)),
                dev.ic.kotlin.candid.fieldId("cid") to dev.ic.kotlin.candid.CandidValue.CandidText(sealed.cid),
                dev.ic.kotlin.candid.fieldId("evmAddress") to dev.ic.kotlin.candid.CandidValue.CandidText(address),
                dev.ic.kotlin.candid.fieldId("transportPublicKey") to dev.ic.kotlin.candid.CandidValue.CandidBlob(transport.publicKey),
                dev.ic.kotlin.candid.fieldId("nonce") to dev.ic.kotlin.candid.CandidValue.CandidNat(nonceNat),
                dev.ic.kotlin.candid.fieldId("signature") to dev.ic.kotlin.candid.CandidValue.CandidBlob(sigBytes),
                dev.ic.kotlin.candid.fieldId("eip712ChainId") to dev.ic.kotlin.candid.CandidValue.CandidNat(
                    java.math.BigInteger.valueOf(domainChainId),
                ),
                dev.ic.kotlin.candid.fieldId("eip712VerifyingContract") to dev.ic.kotlin.candid.CandidValue.CandidText(
                    GateRequestBuilder.EIP712_VERIFYING_CONTRACT,
                ),
            ),
        )
        return try {
            val replyArg = callCanister("requestDecryptionKey", dev.ic.kotlin.candid.CandidEncoder.encode(listOf(record)))
                .getOrElse { return Result.failure(it) }
            val decoded = try {
                dev.ic.kotlin.candid.CandidDecoder.decode(replyArg)
            } catch (_: Exception) {
                null
            } ?: return Result.failure(HavenError.CanisterCallFailed("Canister returned an unreadable response."))
            val keys = parseGateKeyResult(decoded).getOrElse { return Result.failure(it) }
            val derivation = vetkdDerivationInput(chainVariant, sealed.tokenAddress, thresholdNorm, sealed.cid)
            val aesKey = vetKdUnwrap.unwrapContentKey(
                haven.mobile.core.haven.aol.vetkeys.UnwrapParams(
                    encryptedVetKey = keys.encryptedKey,
                    transportSecret = transport.secretKey,
                    verificationKey = keys.verificationKey,
                    derivationInput = derivation,
                    sealedKeyUtf8 = sealed.encryptedAesKey.toByteArray(Charsets.UTF_8),
                ),
            ).getOrElse {
                timber.log.Timber.w(it, "VetKD unwrap failed")
                return Result.failure(HavenError.PlaybackDecryptFailed("The sealed key would not open (${it.message})."))
            }
            if (aesKey.size != 32) {
                return Result.failure(HavenError.PlaybackDecryptFailed("The sealed key unwrapped to the wrong size."))
            }
            // TEMP-DIAG (mp3 bad_decrypt): the unwrap can succeed with a wrong key when the
            // request/derivation inputs disagree with the wrap identity (IBE gives garbage,
            // not an error). Log everything needed to compare against the CLI-side repro:
            // expected derivation 6ab58693… and key sha256 27baa5f2… for the Sepolia mp3.
            timber.log.Timber.d(
                "sealed unlock diag chain=%s token=%s thr=%s cid=%s deriv=%s keySha=%s",
                chainVariant,
                sealed.tokenAddress,
                thresholdNorm,
                sealed.cid,
                derivation.toHex(),
                java.security.MessageDigest.getInstance("SHA-256").digest(aesKey).toHex(),
            )
            aesKeyCache.put(cacheKey, aesKey)
            Result.success(aesKey)
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Sealed v1 unlock failed")
            Result.failure(HavenError.CanisterCallFailed("Haven couldn't unlock this sealed item."))
        }
    }

    // ── Protocols v3 and v4: per-(gate, epoch[, target]) seals ───────────────────────────
    //
    // v3 (gate_type 3): one VetKey per (chain, token, threshold, epoch).
    // v4 (gate_type 4, market-cap drip): the same plus the stage's `marketCapTarget`, and the
    // canister refuses to derive until the gate token's Bond-curve market cap reaches it.
    // Both sign (wallet, transport key, epoch[, target], nonce) — never a CID — so one unlock
    // serves every item sealed under the same bucket, each still opening its own
    // `encryptedAesKey` on device.

    /**
     * Everything that identifies one epoch-bucket VetKey on the canister side, plus the wallet
     * whose access was checked. [epoch] is the *effective* epoch (0 when threshold is 0), so
     * records the canister collapses together share one entry. [marketCapTarget] is null for
     * v3 and set for v4 — different stages of one drip are different keys.
     */
    internal data class EpochGateKey(
        val protocol: Long,
        val chainVariant: String,
        val tokenAddress: String,
        val threshold: java.math.BigInteger,
        val epoch: java.math.BigInteger,
        val marketCapTarget: java.math.BigInteger?,
        val walletLower: String,
    )

    /**
     * A v3/v4 unlock kept for the rest of the session: the canister's transport-encrypted
     * VetKey, the transport secret that opens it, and the bundled verification key. Holding
     * these — rather than one AES key — lets every *other* item of the bucket open with no
     * further signature or canister call. Memory only; zeroed on [clearFor].
     */
    private class EpochUnlock(
        val encryptedVetKey: ByteArray,
        val transportSecret: ByteArray,
        val verificationKey: ByteArray,
    ) {
        fun wipe() {
            encryptedVetKey.fill(0); transportSecret.fill(0); verificationKey.fill(0)
        }
    }

    private val epochUnlocks = mutableMapOf<EpochGateKey, EpochUnlock>()
    private val epochUnlocksMutex = Mutex()

    /** One in-flight canister request per bucket: concurrent opens share one signature. */
    private val epochLocks = mutableMapOf<EpochGateKey, Mutex>()

    /** Validated inputs for one sealed v3/v4 record. */
    internal data class EpochInputs(
        val key: EpochGateKey,
        /** Epoch as written in the record — what the request carries (the canister collapses). */
        val requestEpoch: java.math.BigInteger,
        val domainChainId: Long,
        val derivationInput: ByteArray,
        /** v4 only: the Bond contract the canister prices with. Blank for v3. */
        val oracleAddress: String,
    )

    internal fun epochInputsOrError(
        sealed: haven.mobile.core.domain.GateMetadata.Sealed,
        address: String,
    ): Result<EpochInputs> {
        val incomplete = Result.failure<EpochInputs>(
            HavenError.UnsupportedGateMetadata("This item's seal record is incomplete — this build can't unwrap it."),
        )
        val protocol = sealed.version
        if (protocol != 3L && protocol != 4L) return incomplete
        val epoch = sealed.epoch
        if (sealed.chain.isBlank() || sealed.tokenAddress.isBlank() || epoch == null) return incomplete
        val target = if (protocol == 4L) {
            val t = sealed.marketCapTarget
            if (t == null || sealed.oracleAddress.isBlank()) return incomplete
            java.math.BigInteger.valueOf(t)
        } else {
            null
        }
        val threshold = parseEpochThreshold(sealed.threshold) ?: return Result.failure(
            HavenError.UnsupportedGateMetadata("This item's seal record has an invalid threshold."),
        )
        val gateChain = haven.mobile.core.domain.HavenChain.parse(sealed.chain)
            ?: return Result.failure(
                HavenError.UnsupportedGateMetadata("This item is gated on a network Haven can't check."),
            )
        val requestEpoch = java.math.BigInteger.valueOf(epoch)
        val effectiveEpoch = if (threshold.signum() == 0) java.math.BigInteger.ZERO else requestEpoch
        val derivation = if (target == null) {
            vetkdDerivationInputV3(gateChain.aolVariant, sealed.tokenAddress, threshold, effectiveEpoch)
        } else {
            vetkdDerivationInputV4(gateChain.aolVariant, sealed.tokenAddress, threshold, effectiveEpoch, target)
        }
        return Result.success(
            EpochInputs(
                key = EpochGateKey(
                    protocol = protocol,
                    chainVariant = gateChain.aolVariant,
                    tokenAddress = sealed.tokenAddress,
                    threshold = threshold,
                    epoch = effectiveEpoch,
                    marketCapTarget = target,
                    walletLower = address.lowercase(),
                ),
                requestEpoch = requestEpoch,
                domainChainId = gateChain.chainId,
                derivationInput = derivation,
                oracleAddress = if (target == null) "" else sealed.oracleAddress,
            ),
        )
    }

    /**
     * VetKD v3/v4 unlock — parity with the canister's `requestDecryptionKeyV3` /
     * `requestDecryptionKeyV4`: transport keypair, versioned signature, canister call, then the
     * device unwrap of the record's `encryptedAesKey` with the versioned derivation input as IBE
     * identity. The reply is kept in [epochUnlocks]; later items of the bucket unwrap locally.
     *
     * v4 first asks the canister's `getMarketCap` (no signature) and stops with
     * [HavenError.MarketCapNotReached] while the stage is still locked, so a reader is never
     * prompted to sign for an unlock the canister would refuse.
     */
    private suspend fun decryptSealedEpoch(
        item: MediaItem,
        sealed: haven.mobile.core.domain.GateMetadata.Sealed,
        session: WalletSession,
        address: String,
        cacheKey: String,
    ): Result<ByteArray> {
        if (!vetKdUnwrap.isAvailable()) {
            return Result.failure(
                HavenError.Internal("Sealed unlock needs the native vetkeys library, which is missing from this build."),
            )
        }
        val inputs = epochInputsOrError(sealed, address).getOrElse { return Result.failure(it) }
        val lock = epochUnlocksMutex.withLock { epochLocks.getOrPut(inputs.key) { Mutex() } }
        val unlock = lock.withLock {
            epochUnlocksMutex.withLock { epochUnlocks[inputs.key] }
                ?: run {
                    inputs.key.marketCapTarget?.let { target ->
                        marketCapGate(inputs, target)?.let { return Result.failure(it) }
                    }
                    requestEpochUnlock(inputs, session, address).getOrElse { return Result.failure(it) }
                        .also { fresh -> epochUnlocksMutex.withLock { epochUnlocks[inputs.key] = fresh } }
                }
        }
        return unwrapWithEpochUnlock(item, sealed, inputs, unlock, cacheKey)
    }

    /**
     * v4 pre-check. [HavenError.MarketCapNotReached] when the live cap is below [target];
     * null when the stage is open — or when the check itself failed, in which case the signed
     * request goes ahead and the canister's own check (the authority) decides.
     */
    private suspend fun marketCapGate(inputs: EpochInputs, target: java.math.BigInteger): HavenError? {
        val cap = marketCap(inputs.key.chainVariant, inputs.key.tokenAddress, inputs.oracleAddress)
            .getOrElse {
                timber.log.Timber.w(it, "getMarketCap pre-check failed; deferring to the signed request")
                return null
            }
        return if (cap < target) HavenError.MarketCapNotReached(required = target, actual = cap) else null
    }

    /**
     * Canister `getMarketCap(chain, token, oracle)`: live Bond-curve market cap in whole reserve
     * units — the same unit as `marketCapTarget`. Anonymous, no signature.
     */
    internal open suspend fun marketCap(
        chainVariant: String,
        tokenAddress: String,
        oracleAddress: String,
    ): Result<java.math.BigInteger> {
        val arg = dev.ic.kotlin.candid.CandidEncoder.encode(
            listOf(
                dev.ic.kotlin.candid.CandidValue.CandidVariant(
                    dev.ic.kotlin.candid.fieldId(chainVariant), dev.ic.kotlin.candid.CandidValue.CandidNull,
                ),
                dev.ic.kotlin.candid.CandidValue.CandidText(tokenAddress),
                dev.ic.kotlin.candid.CandidValue.CandidText(oracleAddress),
            ),
        )
        val reply = callCanister("getMarketCap", arg).getOrElse { return Result.failure(it) }
        return parseMarketCapReply(reply)
    }

    /** `variant { ok : nat; err : text }` -> cap, by field id. */
    internal fun parseMarketCapReply(reply: ByteArray): Result<java.math.BigInteger> {
        val variant = runCatching { dev.ic.kotlin.candid.CandidDecoder.decode(reply) }.getOrNull()
            ?.firstOrNull() as? dev.ic.kotlin.candid.CandidValue.CandidVariant
            ?: return Result.failure(HavenError.CanisterCallFailed("Canister returned an unreadable market cap."))
        if (variant.tag == dev.ic.kotlin.candid.fieldId("ok")) {
            val nat = variant.value as? dev.ic.kotlin.candid.CandidValue.CandidNat
                ?: return Result.failure(HavenError.CanisterCallFailed("Canister returned an unreadable market cap."))
            return Result.success(nat.value)
        }
        val message = (variant.value as? dev.ic.kotlin.candid.CandidValue.CandidText)?.value ?: "unknown"
        return Result.failure(HavenError.CanisterCallFailed("Market cap unavailable ($message)."))
    }

    /** One signature + one `requestDecryptionKeyV3`/`V4` call for a bucket. */
    private suspend fun requestEpochUnlock(
        inputs: EpochInputs,
        session: WalletSession,
        address: String,
    ): Result<EpochUnlock> {
        val transport = vetKdUnwrap.generateTransportKeypair().getOrElse {
            timber.log.Timber.w(it, "VetKD transport keypair failed")
            return Result.failure(HavenError.Internal("Could not prepare the sealed unlock."))
        }
        try {
            val nonce = nonceManager.getNonce(address, config.canisterId)
            val nonceNat = try {
                java.math.BigInteger(nonce)
            } catch (_: Exception) {
                return Result.failure(HavenError.Internal("Could not prepare the sealed unlock."))
            }
            val transportHex = "0x" + transport.publicKey.toHex()
            val target = inputs.key.marketCapTarget
            val typedData = if (target == null) {
                gateRequestBuilder.buildV3Request(
                    evmAddress = address,
                    transportPublicKeyHex = transportHex,
                    epochDecimal = inputs.requestEpoch.toString(),
                    nonceDecimal = nonce,
                    domainChainId = inputs.domainChainId,
                )
            } else {
                gateRequestBuilder.buildV4Request(
                    evmAddress = address,
                    transportPublicKeyHex = transportHex,
                    epochDecimal = inputs.requestEpoch.toString(),
                    marketCapTargetDecimal = target.toString(),
                    nonceDecimal = nonce,
                    domainChainId = inputs.domainChainId,
                )
            }
            val sig = signSerialized(session, typedData, inputs.domainChainId).getOrElse {
                return Result.failure(HavenError.CanisterCallFailed("Signing failed: ${it.message}"))
            }
            val sigBytes = parseWalletSignature(sig) ?: return Result.failure(
                HavenError.InvalidSignatureFormat("The wallet returned an unusable signature."),
            )
            val record = buildEpochCandidRequest(inputs, address, transport.publicKey, nonceNat, sigBytes)
            val method = if (target == null) "requestDecryptionKeyV3" else "requestDecryptionKeyV4"
            val replyArg = callCanister(method, dev.ic.kotlin.candid.CandidEncoder.encode(listOf(record)))
                .getOrElse { return Result.failure(it) }
            val decoded = try {
                dev.ic.kotlin.candid.CandidDecoder.decode(replyArg)
            } catch (_: Exception) {
                null
            } ?: return Result.failure(HavenError.CanisterCallFailed("Canister returned an unreadable response."))
            val keys = parseGateKeyResult(decoded).getOrElse { return Result.failure(it) }
            return Result.success(EpochUnlock(keys.encryptedKey, transport.secretKey.copyOf(), keys.verificationKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Sealed v%s unlock failed", inputs.key.protocol)
            return Result.failure(HavenError.CanisterCallFailed("Haven couldn't unlock this sealed item."))
        } finally {
            transport.secretKey.fill(0)
        }
    }

    /**
     * Candid `GateRequestV3` / `GateRequestV4` exactly as `backend.did` declares them. Field
     * ids, not order, decide the wire layout; the verifying contract matches the signed domain.
     */
    internal fun buildEpochCandidRequest(
        inputs: EpochInputs,
        address: String,
        transportPublicKey: ByteArray,
        nonce: java.math.BigInteger,
        signature: ByteArray,
    ): dev.ic.kotlin.candid.CandidValue.CandidRecord {
        val fields = mutableMapOf(
            dev.ic.kotlin.candid.fieldId("chain") to dev.ic.kotlin.candid.CandidValue.CandidVariant(
                dev.ic.kotlin.candid.fieldId(inputs.key.chainVariant), dev.ic.kotlin.candid.CandidValue.CandidNull,
            ),
            dev.ic.kotlin.candid.fieldId("tokenAddress") to dev.ic.kotlin.candid.CandidValue.CandidText(inputs.key.tokenAddress),
            dev.ic.kotlin.candid.fieldId("threshold") to dev.ic.kotlin.candid.CandidValue.CandidNat(inputs.key.threshold),
            dev.ic.kotlin.candid.fieldId("epoch") to dev.ic.kotlin.candid.CandidValue.CandidNat(inputs.requestEpoch),
            dev.ic.kotlin.candid.fieldId("evmAddress") to dev.ic.kotlin.candid.CandidValue.CandidText(address),
            dev.ic.kotlin.candid.fieldId("transportPublicKey") to dev.ic.kotlin.candid.CandidValue.CandidBlob(transportPublicKey),
            dev.ic.kotlin.candid.fieldId("nonce") to dev.ic.kotlin.candid.CandidValue.CandidNat(nonce),
            dev.ic.kotlin.candid.fieldId("signature") to dev.ic.kotlin.candid.CandidValue.CandidBlob(signature),
            dev.ic.kotlin.candid.fieldId("eip712ChainId") to dev.ic.kotlin.candid.CandidValue.CandidNat(
                java.math.BigInteger.valueOf(inputs.domainChainId),
            ),
            dev.ic.kotlin.candid.fieldId("eip712VerifyingContract") to dev.ic.kotlin.candid.CandidValue.CandidText(
                GateRequestBuilder.EIP712_VERIFYING_CONTRACT,
            ),
        )
        inputs.key.marketCapTarget?.let { target ->
            fields[dev.ic.kotlin.candid.fieldId("marketCapTarget")] = dev.ic.kotlin.candid.CandidValue.CandidNat(target)
            fields[dev.ic.kotlin.candid.fieldId("oracleAddress")] = dev.ic.kotlin.candid.CandidValue.CandidText(inputs.oracleAddress)
        }
        return dev.ic.kotlin.candid.CandidValue.CandidRecord(fields)
    }

    private fun unwrapWithEpochUnlock(
        item: MediaItem,
        sealed: haven.mobile.core.domain.GateMetadata.Sealed,
        inputs: EpochInputs,
        unlock: EpochUnlock,
        cacheKey: String,
    ): Result<ByteArray> {
        // unwrapContentKey zeroes the transport secret it is handed — pass copies so the
        // cached unlock keeps serving the rest of the bucket.
        val aesKey = vetKdUnwrap.unwrapContentKey(
            haven.mobile.core.haven.aol.vetkeys.UnwrapParams(
                encryptedVetKey = unlock.encryptedVetKey.copyOf(),
                transportSecret = unlock.transportSecret.copyOf(),
                verificationKey = unlock.verificationKey.copyOf(),
                derivationInput = inputs.derivationInput,
                sealedKeyUtf8 = sealed.encryptedAesKey.toByteArray(Charsets.UTF_8),
            ),
        ).getOrElse {
            timber.log.Timber.w(it, "VetKD v%s unwrap failed for %s", inputs.key.protocol, item.id)
            return Result.failure(HavenError.PlaybackDecryptFailed("The sealed key would not open (${it.message})."))
        }
        if (aesKey.size != 32) {
            return Result.failure(HavenError.PlaybackDecryptFailed("The sealed key unwrapped to the wrong size."))
        }
        aesKeyCache.put(cacheKey, aesKey)
        return Result.success(aesKey)
    }

    /**
     * v3 derivation input — canister `computeDerivationInputV3`:
     * `SHA-256("accessol_v3:{chain}:{token}:{threshold}:{epoch}")`, token casing verbatim,
     * decimals canonical. Callers pass the *effective* epoch.
     * Pinned against `haven-aol/tests/fixtures/derivation-v3-vectors.json`.
     */
    internal fun vetkdDerivationInputV3(
        chainVariant: String,
        tokenAddress: String,
        threshold: java.math.BigInteger,
        epoch: java.math.BigInteger,
    ): ByteArray = sha256Utf8("accessol_v3:$chainVariant:$tokenAddress:$threshold:$epoch")

    /**
     * v4 derivation input — canister `computeDerivationInputV4`:
     * `SHA-256("accessol_v4:{chain}:{token}:{threshold}:{epoch}:{marketCapTarget}")`.
     * Pinned against `haven-aol/tests/fixtures/derivation-v4-vectors.json`.
     */
    internal fun vetkdDerivationInputV4(
        chainVariant: String,
        tokenAddress: String,
        threshold: java.math.BigInteger,
        epoch: java.math.BigInteger,
        marketCapTarget: java.math.BigInteger,
    ): ByteArray = sha256Utf8("accessol_v4:$chainVariant:$tokenAddress:$threshold:$epoch:$marketCapTarget")

    private fun sha256Utf8(preimage: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8))

    /**
     * v3/v4 thresholds are taken verbatim as a canonical Nat — unlike v1 they are NOT coerced
     * to at least 1: threshold 0 is the free tier, and the derivation must match what the
     * uploader sealed under. Null (fail closed) for anything that is not a plain decimal.
     */
    internal fun parseEpochThreshold(raw: String): java.math.BigInteger? {
        val t = raw.trim()
        if (!Regex("^(0|[1-9][0-9]*)$").matches(t)) return null
        return java.math.BigInteger(t)
    }

    /** Bucket key for a sealed v3/v4 item, or null when it isn't one (or can't be unlocked). */
    internal fun epochKeyOrNull(item: MediaItem, address: String?): EpochGateKey? {
        val sealed = item.encryptionMetadata as? haven.mobile.core.domain.GateMetadata.Sealed ?: return null
        if (address == null || sealed.gateTypeConflict) return null
        return epochInputsOrError(sealed, address).getOrNull()?.key
    }

    override suspend fun precheck(item: MediaItem): HavenError? {
        if (config.canisterId.isBlank() || config.icHost.isBlank()) return null
        val sealed = item.encryptionMetadata as? haven.mobile.core.domain.GateMetadata.Sealed ?: return null
        if (!sealed.isMarketCapDrip || sealed.gateTypeConflict) return null
        // The market cap is wallet-independent; with no wallet the session-cache lookup simply
        // misses and the cap check still runs.
        val inputs = epochInputsOrError(sealed, walletSession.address.value ?: "").getOrNull() ?: return null
        if (epochUnlocksMutex.withLock { epochUnlocks.containsKey(inputs.key) }) return null
        val target = inputs.key.marketCapTarget ?: return null
        return marketCapGate(inputs, target)
    }

    /** Session already holds the bucket unlock for this sealed v3/v4 item. */
    private suspend fun hasEpochUnlock(item: MediaItem): Boolean {
        val key = epochKeyOrNull(item, walletSession.address.value) ?: return false
        return epochUnlocksMutex.withLock { epochUnlocks.containsKey(key) }
    }

    /**
     * Pre-seal gate shapes (`wrappedKey` records without `encryptedAesKey`). No writer emits
     * them, and the canister has no endpoint that returns a usable key for them: every live
     * gate is a VetKD seal routed above. Fail closed before any signing prompt.
     *
     * (This used to sign a made-up `GateRequestV3(itemId, gate, nonce, epoch)` under a
     * `Haven-AOL` v3 domain and treat the transport-encrypted VetKey as the AES key — a request
     * the canister rejects and a key that could never decrypt. Real v3 content is
     * [decryptSealedEpoch].)
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun decryptLegacy(
        item: MediaItem,
        gate: haven.mobile.core.domain.TokenGate,
        session: WalletSession,
        address: String,
        cacheKey: String,
    ): Result<ByteArray> = Result.failure(
        HavenError.UnsupportedGateMetadata("This item uses a legacy gate shape this build can't unlock."),
    )

    /**
     * Raw canister call, seammed for tests: production hits the network, tests override with a
     * canned reply. Returns the reply argument bytes, or the rejection as a failure.
     */
    internal open suspend fun callCanister(method: String, candidArg: ByteArray): Result<ByteArray> {
        return try {
            val principal = dev.ic.kotlin.candid.Principal.fromText(config.canisterId)
            // `requestDecryptionKey` runs EVM-RPC checks then VetKD derivation (10s+), and the
            // v3 sync response waits on execution — OkHttp's 10s read default would abort slow
            // but healthy executions, so the shared client carries sized timeouts for that
            // reality. The overall 5-minute poll timeout in IcCallWithPolling still bounds
            // the whole operation.
            val transport = dev.ic.kotlin.agent.OkHttpTransport(config.icHost, icHttp)
            when (val reply = IcCallWithPolling(transport).call(principal, method, candidArg)) {
                is dev.ic.kotlin.agent.Reply.Replied -> Result.success(reply.arg)
                is dev.ic.kotlin.agent.Reply.Rejected ->
                    Result.failure(HavenError.CanisterCallFailed("Canister rejected $method: ${reply.message}"))
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "HavenAol call failed (method=%s)", method)
            Result.failure(HavenError.CanisterCallFailed("Haven couldn't reach the service that unlocks this item."))
        }
    }

    /**
     * `requestDecryptionKey` reply -> bundled keys. Reads `encrypted_key` and
     * `verification_key` by field id (never positionally — record fields sort by hash), and
     * maps `err` variants to the dapp's `mapGateError` messages. Internal so canned replies
     * pin the mapping.
     */
    internal fun parseGateKeyResult(
        decoded: List<dev.ic.kotlin.candid.CandidValue>,
    ): Result<GateKeys> {
        val variant = decoded.firstOrNull() as? dev.ic.kotlin.candid.CandidValue.CandidVariant
            ?: return Result.failure(HavenError.CanisterCallFailed("Canister returned an unexpected response."))
        if (variant.tag == dev.ic.kotlin.candid.fieldId("ok")) {
            val fields = (variant.value as? dev.ic.kotlin.candid.CandidValue.CandidRecord)?.fields
            val encKey = fields?.get(dev.ic.kotlin.candid.fieldId("encrypted_key"))
                as? dev.ic.kotlin.candid.CandidValue.CandidBlob
            val verificationKey = fields?.get(dev.ic.kotlin.candid.fieldId("verification_key"))
                as? dev.ic.kotlin.candid.CandidValue.CandidBlob
            if (encKey == null || verificationKey == null) {
                return Result.failure(HavenError.CanisterCallFailed("Canister returned an incomplete response."))
            }
            return Result.success(GateKeys(encKey.bytes, verificationKey.bytes))
        }
        if (variant.tag == dev.ic.kotlin.candid.fieldId("err")) {
            return Result.failure(mapGateError(variant.value))
        }
        return Result.failure(HavenError.CanisterCallFailed("Canister returned an unexpected response."))
    }

    /**
     * `batchRequestDecryptionKey` reply -> per-cid keys. Field ids, never positions.
     * `err` variants share the single-call mapping; a malformed `keys` entry is
     * skipped rather than failing the group (the missing cid then fails closed
     * per item, exactly like a single call that returned no key).
     */
    internal fun parseBatchKeyResult(
        decoded: List<dev.ic.kotlin.candid.CandidValue>,
    ): Result<BatchKeyBundle> {
        val variant = decoded.firstOrNull() as? dev.ic.kotlin.candid.CandidValue.CandidVariant
            ?: return Result.failure(HavenError.CanisterCallFailed("Canister returned an unexpected response."))
        if (variant.tag == dev.ic.kotlin.candid.fieldId("ok")) {
            val fields = (variant.value as? dev.ic.kotlin.candid.CandidValue.CandidRecord)?.fields
            val keys = fields?.get(dev.ic.kotlin.candid.fieldId("keys"))
                as? dev.ic.kotlin.candid.CandidValue.CandidVec
            val verificationKey = fields?.get(dev.ic.kotlin.candid.fieldId("verification_key"))
                as? dev.ic.kotlin.candid.CandidValue.CandidBlob
            if (keys == null || verificationKey == null) {
                return Result.failure(HavenError.CanisterCallFailed("Canister returned an incomplete response."))
            }
            val entries = keys.items.mapNotNull { entry ->
                val rec = (entry as? dev.ic.kotlin.candid.CandidValue.CandidRecord)?.fields
                    ?: return@mapNotNull null
                val cid = (rec[dev.ic.kotlin.candid.fieldId("cid")]
                    as? dev.ic.kotlin.candid.CandidValue.CandidText)?.value
                    ?: return@mapNotNull null
                val enc = (rec[dev.ic.kotlin.candid.fieldId("encrypted_key")]
                    as? dev.ic.kotlin.candid.CandidValue.CandidBlob)?.bytes
                    ?: return@mapNotNull null
                BatchKeyEntry(cid = cid, encryptedKey = enc)
            }
            return Result.success(BatchKeyBundle(entries, verificationKey.bytes))
        }
        if (variant.tag == dev.ic.kotlin.candid.fieldId("err")) {
            return Result.failure(mapGateError(variant.value))
        }
        return Result.failure(HavenError.CanisterCallFailed("Canister returned an unexpected response."))
    }

    /** Canister `GateError` variant -> reader-facing failure, mirroring dapp `mapGateError`. */
    internal fun mapGateError(value: dev.ic.kotlin.candid.CandidValue): HavenError {
        val err = value as? dev.ic.kotlin.candid.CandidValue.CandidVariant
            ?: return HavenError.CanisterCallFailed("The unlock request was rejected.")
        fun tag(name: String) = err.tag == dev.ic.kotlin.candid.fieldId(name)
        return when {
            tag("InsufficientBalance") -> {
                val details = err.value as? dev.ic.kotlin.candid.CandidValue.CandidRecord
                val required = (details?.fields?.get(dev.ic.kotlin.candid.fieldId("required"))
                    as? dev.ic.kotlin.candid.CandidValue.CandidNat)?.value?.toString() ?: "?"
                val actual = (details?.fields?.get(dev.ic.kotlin.candid.fieldId("actual"))
                    as? dev.ic.kotlin.candid.CandidValue.CandidNat)?.value?.toString() ?: "0"
                HavenError.GateVerificationFailed(
                    "Insufficient token balance. Required: $required, your balance: $actual. " +
                        "Make sure you hold the required tokens on the correct chain.",
                )
            }
            tag("InvalidSignature") -> HavenError.SigningFailed(
                "Invalid signature. Please try signing again with your wallet.",
            )
            tag("NonceAlreadyUsed") -> HavenError.Internal(
                "This decrypt request was already submitted (nonce replay protection). " +
                    "Try playing the video again — you should only need one wallet signature.",
            )
            tag("InvalidAddress") -> HavenError.CanisterCallFailed(
                "Invalid address: ${(err.value as? dev.ic.kotlin.candid.CandidValue.CandidText)?.value ?: "?"}",
            )
            tag("EvmRpcError") -> HavenError.CanisterCallFailed(
                "Balance check failed (${(err.value as? dev.ic.kotlin.candid.CandidValue.CandidText)?.value ?: "RPC error"}). Try again.",
            )
            tag("VetKDError") -> HavenError.CanisterCallFailed(
                "Key service error (${(err.value as? dev.ic.kotlin.candid.CandidValue.CandidText)?.value ?: "unknown"}). Try again.",
            )
            tag("InvalidThreshold") -> HavenError.CanisterCallFailed("The gate threshold is invalid.")
            tag("MarketCapNotReached") -> {
                val details = (err.value as? dev.ic.kotlin.candid.CandidValue.CandidRecord)?.fields
                fun nat(name: String) = (details?.get(dev.ic.kotlin.candid.fieldId(name))
                    as? dev.ic.kotlin.candid.CandidValue.CandidNat)?.value ?: java.math.BigInteger.ZERO
                HavenError.MarketCapNotReached(required = nat("required"), actual = nat("actual"))
            }
            tag("InvalidOracle") -> HavenError.UnsupportedGateMetadata(
                "This premiere's price oracle isn't one the key service accepts " +
                    "(${(err.value as? dev.ic.kotlin.candid.CandidValue.CandidText)?.value ?: "unknown"}).",
            )
            tag("InvalidEpoch") -> HavenError.CanisterCallFailed(
                "This item's unlock period hasn't started yet on the key service. Check your device clock and try again.",
            )
            else -> HavenError.CanisterCallFailed("The unlock request was rejected.")
        }
    }

    /**
     * VetKD derivation input — dapp parity with `computeDerivationInput` (derivation-spec.md):
     * the preimage binds the gate the key derives for, and doubles as the IBE identity at
     * unwrap. Any drift here derives a key that cannot open the sealed record.
     */
    internal fun vetkdDerivationInput(
        chainVariant: String,
        tokenAddress: String,
        thresholdNorm: String,
        cid: String,
    ): ByteArray {
        val preimage = "accessol:$chainVariant:$tokenAddress:$thresholdNorm:$cid"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(preimage.toByteArray(Charsets.UTF_8))
    }

    /** Record threshold -> positive integer string, mirroring `normalizeDerivationThreshold`. */
    internal fun normalizeSealedThreshold(raw: String): String =
        raw.trim().toLongOrNull()?.coerceAtLeast(1L)?.toString() ?: "1"

    /**
     * Batch commitment for `batchRequestDecryptionKey`: `keccak256` over the
     * concatenated per-cid derivation inputs, in submitted order — exactly the
     * canister's `eip712BatchGateStructHash` commitment. Same inputs the single
     * path derives with, so one formula serves signing and unwrapping.
     */
    internal fun batchCidsCommitmentHex(
        chainVariant: String,
        tokenAddress: String,
        thresholdNorm: String,
        cids: List<String>,
    ): String {
        require(cids.isNotEmpty() && cids.size <= MAX_BATCH_CIDS) {
            "batch needs 1..$MAX_BATCH_CIDS cids"
        }
        val packed = ByteArray(32 * cids.size)
        cids.forEachIndexed { index, cid ->
            vetkdDerivationInput(chainVariant, tokenAddress, thresholdNorm, cid)
                .copyInto(packed, index * 32)
        }
        return "0x" + Keccak256.hashHex(packed)
    }

    /**
     * True v1 batch unlock: one transport keypair, one nonce, ONE wallet signature
     * and ONE canister call (single EVM check) for every cid in the group, then a
     * per-cid local unwrap. Fails closed per item — a missing key or bad unwrap
     * for one cid never poisons its neighbours.
     */
    private suspend fun decryptBatchSealedV1(
        items: List<MediaItem>,
        key: V1BatchKey,
        session: WalletSession,
        address: String,
    ): List<Result<ByteArray>> {
        fun allFailed(throwable: Throwable): List<Result<ByteArray>> =
            items.map { Result.failure<ByteArray>(throwable) }
        if (!vetKdUnwrap.isAvailable()) {
            return allFailed(
                HavenError.Internal("Sealed unlock needs the native vetkeys library, which is missing from this build."),
            )
        }
        val transport = vetKdUnwrap.generateTransportKeypair().getOrElse {
            timber.log.Timber.w(it, "VetKD transport keypair failed")
            return allFailed(HavenError.Internal("Could not prepare the sealed unlock."))
        }
        val nonce = nonceManager.getNonce(address, config.canisterId)
        val cids = items.map { (it.encryptionMetadata as haven.mobile.core.domain.GateMetadata.Sealed).cid }
        val commitment = batchCidsCommitmentHex(key.chainVariant, key.tokenAddress, key.thresholdNorm, cids)
        // The batch struct hashes the transport key itself (`bytes32 transportKeyHash`),
        // unlike the single request where the wallet hashes the dynamic `bytes` field —
        // so the raw key goes in the Candid call but only its keccak goes in the typed data.
        val domainChainId = eip155ForChainVariant(key.chainVariant)
        val typedData = gateRequestBuilder.buildBatchV1Request(
            evmAddress = address,
            transportKeyHashHex = "0x" + Keccak256.hashHex(transport.publicKey),
            cidsCommitmentHex = commitment,
            nonceDecimal = nonce,
            domainChainId = domainChainId,
        )
        val sig = signSerialized(session, typedData, domainChainId).getOrElse {
            return allFailed(HavenError.CanisterCallFailed("Signing failed: ${it.message}"))
        }
        val sigBytes = parseWalletSignature(sig) ?: return allFailed(
            HavenError.InvalidSignatureFormat("The wallet returned an unusable signature."),
        )
        val nonceNat = try {
            java.math.BigInteger(nonce)
        } catch (_: Exception) {
            return allFailed(HavenError.Internal("Could not prepare the sealed unlock."))
        }
        val record = dev.ic.kotlin.candid.CandidValue.CandidRecord(
            mapOf(
                dev.ic.kotlin.candid.fieldId("chain") to dev.ic.kotlin.candid.CandidValue.CandidVariant(
                    dev.ic.kotlin.candid.fieldId(key.chainVariant), dev.ic.kotlin.candid.CandidValue.CandidNull,
                ),
                dev.ic.kotlin.candid.fieldId("tokenAddress") to dev.ic.kotlin.candid.CandidValue.CandidText(key.tokenAddress),
                dev.ic.kotlin.candid.fieldId("threshold") to dev.ic.kotlin.candid.CandidValue.CandidNat(java.math.BigInteger(key.thresholdNorm)),
                dev.ic.kotlin.candid.fieldId("cids") to dev.ic.kotlin.candid.CandidValue.CandidVec(
                    cids.map { dev.ic.kotlin.candid.CandidValue.CandidText(it) },
                ),
                dev.ic.kotlin.candid.fieldId("evmAddress") to dev.ic.kotlin.candid.CandidValue.CandidText(address),
                dev.ic.kotlin.candid.fieldId("transportPublicKey") to dev.ic.kotlin.candid.CandidValue.CandidBlob(transport.publicKey),
                dev.ic.kotlin.candid.fieldId("nonce") to dev.ic.kotlin.candid.CandidValue.CandidNat(nonceNat),
                dev.ic.kotlin.candid.fieldId("signature") to dev.ic.kotlin.candid.CandidValue.CandidBlob(sigBytes),
                dev.ic.kotlin.candid.fieldId("eip712ChainId") to dev.ic.kotlin.candid.CandidValue.CandidNat(
                    java.math.BigInteger.valueOf(domainChainId),
                ),
                dev.ic.kotlin.candid.fieldId("eip712VerifyingContract") to dev.ic.kotlin.candid.CandidValue.CandidText(
                    GateRequestBuilder.EIP712_VERIFYING_CONTRACT,
                ),
            ),
        )
        val replyArg = try {
            callCanister("batchRequestDecryptionKey", dev.ic.kotlin.candid.CandidEncoder.encode(listOf(record)))
                .getOrElse { return allFailed(it) }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Sealed v1 batch unlock failed")
            return allFailed(HavenError.CanisterCallFailed("Haven couldn't unlock these sealed items."))
        }
        val decoded = try {
            dev.ic.kotlin.candid.CandidDecoder.decode(replyArg)
        } catch (_: Exception) {
            null
        } ?: return allFailed(HavenError.CanisterCallFailed("Canister returned an unreadable response."))
        val batch = parseBatchKeyResult(decoded).getOrElse { return allFailed(it) }
        val byCid = batch.entries.associate { it.cid to it.encryptedKey }
        return items.map { item ->
            val sealed = item.encryptionMetadata as haven.mobile.core.domain.GateMetadata.Sealed
            val encryptedVetKey = byCid[sealed.cid]
                ?: return@map Result.failure<ByteArray>(
                    HavenError.CanisterCallFailed("Canister returned no key for this item."),
                )
            val derivation = vetkdDerivationInput(key.chainVariant, key.tokenAddress, key.thresholdNorm, sealed.cid)
            val aesKey = vetKdUnwrap.unwrapContentKey(
                haven.mobile.core.haven.aol.vetkeys.UnwrapParams(
                    encryptedVetKey = encryptedVetKey,
                    transportSecret = transport.secretKey,
                    verificationKey = batch.verificationKey,
                    derivationInput = derivation,
                    sealedKeyUtf8 = sealed.encryptedAesKey.toByteArray(Charsets.UTF_8),
                ),
            ).getOrElse {
                timber.log.Timber.w(it, "VetKD batch unwrap failed")
                return@map Result.failure<ByteArray>(HavenError.PlaybackDecryptFailed("The sealed key would not open (${it.message})."))
            }
            if (aesKey.size != 32) {
                return@map Result.failure<ByteArray>(HavenError.PlaybackDecryptFailed("The sealed key unwrapped to the wrong size."))
            }
            aesKeyCache.put(cacheKeyFor(item), aesKey)
            Result.success(aesKey)
        }
    }

    /**
     * Wallet signature -> 65 bytes, mirroring `parseSignatureHex`. Anything else fails closed
     * before the Candid call rather than encoding a short signature.
     */
    internal fun parseWalletSignature(sig: String): ByteArray? {
        val hex = sig.removePrefix("0x")
        if (hex.length != 130 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(65) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    override suspend fun verificationKey(): Result<ByteArray> {
        if (config.canisterId.isBlank()) return Result.failure(HavenError.CanisterCallFailed("verificationKey not configured"))
        aesKeyCache.get("verificationKey:${config.canisterId}")?.let { return Result.success(it) }
        return try {
            val principal = dev.ic.kotlin.candid.Principal.fromText(config.canisterId)
            val transport = dev.ic.kotlin.agent.OkHttpTransport(config.icHost, icHttp)
            val agent = dev.ic.kotlin.agent.IcAgent(transport)
            val arg = dev.ic.kotlin.candid.CandidEncoder.encode(emptyList())
            val replyBytes = agent.query(principal, "getVetKDPublicKey", arg)
            val decoded = dev.ic.kotlin.candid.CandidDecoder.decode(replyBytes)
            val first = decoded.firstOrNull()
            val keyBytes: ByteArray? = when (first) {
                is dev.ic.kotlin.candid.CandidValue.CandidBlob -> first.bytes
                is dev.ic.kotlin.candid.CandidValue.CandidVec -> (first.items.firstOrNull() as? dev.ic.kotlin.candid.CandidValue.CandidBlob)?.bytes
                else -> null
            }
            if (keyBytes != null) {
                aesKeyCache.put("verificationKey:${config.canisterId}", keyBytes)
                Result.success(keyBytes)
            } else {
                Result.failure(HavenError.CanisterCallFailed("verificationKey: unexpected Candid shape"))
            }
        } catch (e: Exception) {
            Result.failure(HavenError.CanisterCallFailed("verificationKey query failed for ${config.canisterId}: ${e.message}"))
        }
    }

    override suspend fun attestationPublicKey(): Result<ByteArray> {
        if (config.canisterId.isBlank()) return Result.failure(HavenError.CanisterCallFailed("attestationPublicKey not configured"))
        aesKeyCache.get("attestationPublicKey:${config.canisterId}")?.let { return Result.success(it) }
        return try {
            val principal = dev.ic.kotlin.candid.Principal.fromText(config.canisterId)
            val transport = dev.ic.kotlin.agent.OkHttpTransport(config.icHost, icHttp)
            val agent = dev.ic.kotlin.agent.IcAgent(transport)
            val arg = dev.ic.kotlin.candid.CandidEncoder.encode(emptyList())
            val replyBytes = agent.query(principal, "getAttestationPublicKey", arg)
            val decoded = dev.ic.kotlin.candid.CandidDecoder.decode(replyBytes)
            val first = decoded.firstOrNull()
            val keyBytes: ByteArray? = when (first) {
                is dev.ic.kotlin.candid.CandidValue.CandidBlob -> first.bytes
                is dev.ic.kotlin.candid.CandidValue.CandidVec -> (first.items.firstOrNull() as? dev.ic.kotlin.candid.CandidValue.CandidBlob)?.bytes
                else -> null
            }
            if (keyBytes != null) {
                aesKeyCache.put("attestationPublicKey:${config.canisterId}", keyBytes)
                Result.success(keyBytes)
            } else {
                Result.failure(HavenError.CanisterCallFailed("attestationPublicKey: unexpected Candid shape"))
            }
        } catch (e: Exception) {
            Result.failure(HavenError.CanisterCallFailed("attestationPublicKey query failed for ${config.canisterId}: ${e.message}"))
        }
    }

    override suspend fun hasCachedKey(item: MediaItem): Boolean =
        aesKeyCache.getSuspend(cacheKeyFor(item)) != null || hasEpochUnlock(item)

    /** Session cache identity for an item's key — one formula for lookup and store. */
    private fun cacheKeyFor(item: MediaItem): String =
        "${item.id}:${item.gate?.tokenAddress}:${item.encryptionMetadata?.let { it::class.simpleName } ?: "v1"}"

    /**
     * Batch unlock: sealed-v1 items sharing a gate go out as ONE
     * `batchRequestDecryptionKey` (one signature, one EVM check, per-cid keys
     * back); V3 epoch groups keep one single call whose key is shared; the rest
     * decrypt per item. Groups run concurrently, order is preserved, one item's
     * failure never cancels the rest (`supervisorScope`) — the batch UI states
     * the signature count up front.
     */
    override suspend fun decryptAll(
        items: List<MediaItem>,
        session: WalletSession,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): List<Result<ByteArray>> {
        if (items.isEmpty()) return emptyList()
        return try {
            decryptAllInner(items, session, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The batch screen reports per-item Results — an unexpected throw here
            // used to escape as an app crash with no error screen at all.
            timber.log.Timber.e(e, "decryptAll failed closed for ${items.size} items")
            items.map { Result.failure<ByteArray>(HavenError.Internal("Batch unlock hit an unexpected error.")) }
        }
    }

    private suspend fun decryptAllInner(
        items: List<MediaItem>,
        session: WalletSession,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): List<Result<ByteArray>> {
        // Sealed-v1 items with a complete record share one true batch call per gate.
        // Anything failing the preconditions falls through to single decrypt, which
        // fails it closed before signing — batching never signs for what it can't do.
        val v1Groups = mutableMapOf<V1BatchKey, MutableList<Int>>()
        val rest = mutableListOf<Int>()
        items.forEachIndexed { idx, item ->
            val key = v1BatchKeyOrNull(item)
            if (key != null) v1Groups.getOrPut(key) { mutableListOf() }.add(idx)
            else rest.add(idx)
        }
        val address = session.address.value
        // Sealed v3/v4 grouping over the remainder: one bucket (gate, epoch[, drip target]) =
        // one signature, however many items. Null key (not v3/v4, incomplete, inconsistent)
        // means single decrypt, which fails closed.
        val v3Groups = mutableMapOf<EpochGateKey?, MutableList<Int>>()
        rest.forEach { idx -> v3Groups.getOrPut(epochKeyOrNull(items[idx], address)) { mutableListOf() }.add(idx) }
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        suspend fun finish(pairs: List<Pair<Int, Result<ByteArray>>>): List<Pair<Int, Result<ByteArray>>> {
            onProgress(completed.addAndGet(pairs.size), items.size)
            return pairs
        }
        return supervisorScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<Pair<Int, Result<ByteArray>>>>>()
            // True v1 batches, chunked at the canister cap — one signature per chunk.
            v1Groups.forEach { (key, indices) ->
                indices.chunked(MAX_BATCH_CIDS).forEach { chunk ->
                    jobs += async {
                        val pairs = if (address == null) {
                            chunk.map { idx ->
                                idx to Result.failure<ByteArray>(
                                    HavenError.WalletNotConnected("No wallet connected"),
                                )
                            }
                        } else {
                            val results = decryptBatchSealedV1(chunk.map { items[it] }, key, session, address)
                            chunk.zip(results)
                        }
                        finish(pairs)
                    }
                }
            }
            v3Groups.forEach { (batchKey, indices) ->
                jobs += async {
                    val pairs = if (batchKey != null) {
                        // Sealed v3/v4 bucket (FR-ACL-1). The first item signs and
                        // caches the epoch unlock; the rest unwrap their own encryptedAesKey
                        // from it with no signature. Sequential on purpose: all but the first
                        // are local unwraps, and the per-epoch lock would serialize them anyway.
                        val first = indices.first() to decrypt(items[indices.first()], session)
                        val unlocked = epochUnlocksMutex.withLock { epochUnlocks.containsKey(batchKey) }
                        // No bucket unlock (declined signature, balance, market cap not reached,
                        // canister error): the
                        // rest would fail the same way, one wallet prompt each. Share the error.
                        listOf(first) + indices.drop(1).map { idx ->
                            idx to if (unlocked) decrypt(items[idx], session) else first.second
                        }
                    } else {
                        // Anything else — per-item decrypt preserves existing parity.
                        indices.map { idx -> async { idx to decrypt(items[idx], session) } }.awaitAll()
                    }
                    finish(pairs)
                }
            }
            jobs.awaitAll().flatten().sortedBy { it.first }.map { it.second }
        }
    }

    /**
     * Sealed-v1 batch precondition, mirroring `decryptSealedV1`'s fail-closed
     * gates: v1, complete record, known chain. Null routes to single decrypt,
     * which fails the item closed before any signing prompt.
     */
    private fun v1BatchKeyOrNull(item: MediaItem): V1BatchKey? {
        val sealed = item.encryptionMetadata as? haven.mobile.core.domain.GateMetadata.Sealed
            ?: return null
        if (sealed.version != 1L || sealed.gateTypeConflict) return null
        if (sealed.cid.isBlank() || sealed.chain.isBlank() || sealed.tokenAddress.isBlank()) return null
        val chainVariant = haven.mobile.core.domain.HavenChain.parse(sealed.chain)?.aolVariant
            ?: return null
        return V1BatchKey(
            chainVariant = chainVariant,
            tokenAddress = sealed.tokenAddress,
            thresholdNorm = normalizeSealedThreshold(sealed.threshold),
        )
    }

    override suspend fun clearFor(walletAddress: String) {
        aesKeyCache.clearAll()
        epochUnlocksMutex.withLock {
            epochUnlocks.values.forEach { it.wipe() }
            epochUnlocks.clear()
            epochLocks.clear()
        }
        // NonceManager is per-canister; entry clears nothing — real impl would iterate keys
    }
}

/** Bundled canister reply: transport-encrypted VetKey plus the key that verifies it. */
internal data class GateKeys(
    val encryptedKey: ByteArray,
    val verificationKey: ByteArray,
)

/** One entry of a `BatchGateResult.ok.keys` vector: which cid this key opens. */
internal data class BatchKeyEntry(
    val cid: String,
    val encryptedKey: ByteArray,
)

/** A parsed batch reply: per-cid keys plus the shared verification key. */
internal data class BatchKeyBundle(
    val entries: List<BatchKeyEntry>,
    val verificationKey: ByteArray,
)

/**
 * One sealed-v1 batch group: every item derives under the same gate, so one
 * signature and one EVM check unlocks them all. Thresholds are normalized
 * before grouping — records disagreeing only in spelling share a call.
 */
internal data class V1BatchKey(
    val chainVariant: String,
    val tokenAddress: String,
    val thresholdNorm: String,
)

/**
 * EIP-155 id for a gate chain variant name. The signature domain, the wallet
 * request, and the canister balance check must all name the chain actually
 * gated — Sepolia gates sign Sepolia. Falls back to the dapp default when
 * the variant is unknown (callers fail closed on unknown chains first).
 */
internal fun eip155ForChainVariant(chainVariant: String): Long =
    haven.mobile.core.domain.HavenChain.parse(chainVariant)?.chainId
        ?: GateRequestBuilder.EIP712_CHAIN_ID

/** Canister cap on `BatchGateRequest.cids` — larger groups chunk. */
internal const val MAX_BATCH_CIDS = 20

/**
 * Pooled IC client: `requestDecryptionKey` needs 90s reads (EVM-RPC + VetKD),
 * and one shared pool amortises TLS across unlocks, key fetches and polls.
 */
internal fun defaultIcHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
