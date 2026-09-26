package haven.mobile.core.domain

/**
 * Library labeling language — the single source of truth for the wording decided in
 * `planning/library-labels.md`. Wording only: no layout, no badges, no sections.
 *
 * Two facts, always in this order, joined by `·`: provenance first, shade second —
 * e.g. `My contribution · Members`. These exact strings are the only words used for
 * these facts anywhere in the app (cards, detail screen, filters, empty states).
 */
const val PROVENANCE_SELF = "My contribution"
const val PROVENANCE_POOL = "The pool"

const val SHADE_EVERYONE = "Everyone"
const val SHADE_MEMBERS = "Members"
const val SHADE_COLLECTORS = "Collectors"
const val SHADE_ONLY_YOU = "Only you"

/** Detail sheet, creator address absent — absence of proof, never suspicion. */
const val CREATOR_UNVERIFIABLE = "Contribution not yet verified"

/** Detail sheet, gate closed for this viewer — describes the viewer's state. */
const val GATE_FAILED_HEADLINE = "You don't hold this gate's token"

/**
 * Pre-signature disclosure headline, before any gate verdict exists.
 *
 * This screen appears on every first unlock — including gates that stand open —
 * so it must never claim the gate is closed. [GATE_FAILED_HEADLINE] stays
 * reserved for an actual failed gate check.
 */
const val SIGN_DISCLOSURE_HEADLINE = "Unlock with your wallet"

/** Detail sheet, gate closed — always names what would open it. */
fun gateRequirement(tokenName: String): String = "Requires the $tokenName token"

/**
 * Is this the connected wallet's own upload?
 *
 * Until the DAO contribution-token contract exists, this falls back to comparing the
 * entity's on-chain creator address against the connected wallet, then the owner
 * address. Comparison is case-insensitive; a null or blank wallet is never self.
 */
fun MediaItem.isSelfUpload(walletAddress: String?): Boolean {
    val wallet = walletAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    val creator = creatorAddress?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (creator != null) return creator == wallet
    return owner.trim().lowercase() == wallet
}

/** Provenance fact: [PROVENANCE_SELF] for own uploads, [PROVENANCE_POOL] for the rest. */
fun MediaItem.provenanceLabel(walletAddress: String?): String =
    if (isSelfUpload(walletAddress)) PROVENANCE_SELF else PROVENANCE_POOL

/**
 * Shade fact — answers *who can open this?*
 *
 * Ungated items are [SHADE_EVERYONE]. Gated items follow the token standard:
 * fungible gates are [SHADE_MEMBERS], NFT gates [SHADE_COLLECTORS]. A gated item
 * with no parsable gate record is [SHADE_ONLY_YOU].
 */
fun MediaItem.shadeLabel(): String {
    if (!isEncrypted) return SHADE_EVERYONE
    return when (gate?.tokenStandard) {
        TokenStandard.ERC721, TokenStandard.ERC1155 -> SHADE_COLLECTORS
        TokenStandard.ERC20 -> SHADE_MEMBERS
        null -> SHADE_ONLY_YOU
    }
}

/** Caption grammar: provenance first, shade second, joined by `·` — never reordered. */
fun MediaItem.captionLine(walletAddress: String?): String =
    "${provenanceLabel(walletAddress)} · ${shadeLabel()}"

/**
 * Whether the contributor is checkable on-chain. False only when the entity carries
 * no creator address — the detail sheet then says [CREATOR_UNVERIFIABLE].
 */
fun MediaItem.isCreatorVerifiable(): Boolean = !creatorAddress.isNullOrBlank()

/** Short display name for the gate token, for [gateRequirement]. Never blank. */
fun MediaItem.gateTokenName(): String {
    val address = gate?.tokenAddress?.trim().orEmpty()
    if (address.isEmpty()) return "gate"
    return if (address.length <= 10) address else address.take(6) + "…" + address.takeLast(4)
}

/** `Requires the {name} token` for this item's gate. */
fun MediaItem.gateRequirementLine(): String = gateRequirement(gateTokenName())
