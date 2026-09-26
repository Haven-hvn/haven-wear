//! Haven-AOL VetKD unwrap for Android, behind a two-call JNI surface.
//!
//! This crate is a thin JNI skin over the audited [`ic_vetkeys`] client — the same code family
//! as `@icp-sdk/vetkeys`, which the dapp uses against this canister. All protocol logic (BLS
//! transport keys, VetKey recovery, IBE decrypt) stays inside that dependency; this crate only
//! moves bytes across FFI, maps errors to Java exceptions, and never lets a panic cross the
//! boundary (a panic over FFI aborts the process, so every entry point catches unwind).
//!
//! The two calls mirror the dapp's `crypto.ts` exactly:
//!
//! 1. `transportKeypair` — ephemeral [`TransportSecretKey`] (`createTransportKeyPair`).
//! 2. `unwrapContentKey` — [`recoverVetKey`] + [`ibeDecryptAesKey`] fused into one call so the
//!    intermediate VetKey never leaves native memory.

use ic_vetkeys::{
    DerivedPublicKey, EncryptedVetKey, IbeCiphertext, TransportSecretKey,
    is_valid_transport_public_key_encoding,
};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jbyteArray, jobjectArray};
use rand::RngCore;
use zeroize::Zeroize;

/// 48-byte G1-compressed transport public key + 32-byte secret scalar.
pub fn transport_keypair() -> Result<(Vec<u8>, Vec<u8>), String> {
    let mut seed = [0u8; 32];
    rand::rng().fill_bytes(&mut seed);
    // from_seed only fails on a wrong-length seed, which cannot happen here.
    let secret = TransportSecretKey::from_seed(seed.to_vec()).map_err(|e| e.to_string())?;
    seed.zeroize();
    let public = secret.public_key();
    debug_assert!(is_valid_transport_public_key_encoding(&public));
    Ok((public, secret.serialize()))
}

/// Full unwrap: canister VetKey recovery + IBE-decrypt of the sealed AES key.
///
/// Inputs are exactly what [`HavenAolImpl.decrypt`][kt] holds after the canister round-trip:
/// the bundled `encrypted_key` / `verification_key`, the SHA-256 derivation input, the live
/// transport secret, and the gate record's base64 `encryptedAesKey`. Returns the 32-byte AES
/// content key. Every failure mode reads `Err` — malformed inputs fail closed, never panic.
///
/// [kt]: ../../../../../kotlin/haven/mobile/core/haven/aol/HavenAolImpl.kt
pub fn unwrap_content_key(
    encrypted_vet_key: &[u8],
    transport_secret: &[u8],
    verification_key: &[u8],
    derivation_input: &[u8],
    encrypted_aes_key_b64: &[u8],
) -> Result<Vec<u8>, String> {
    let tsk = TransportSecretKey::deserialize(transport_secret).map_err(|e| e.to_string())?;
    let dpk =
        DerivedPublicKey::deserialize(verification_key).map_err(|e| format!("{e:?}"))?;
    let enc_key = EncryptedVetKey::deserialize(encrypted_vet_key).map_err(|e| e.to_string())?;
    let vet_key = enc_key.decrypt_and_verify(&tsk, &dpk, derivation_input)?;

    let b64 = std::str::from_utf8(encrypted_aes_key_b64)
        .map_err(|_| "encryptedAesKey is not valid UTF-8".to_string())?;
    let ibe_bytes = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        b64.trim(),
    )
    .map_err(|_| "encryptedAesKey is not valid base64".to_string())?;
    let ibe = IbeCiphertext::deserialize(&ibe_bytes).map_err(|e| e.to_string())?;
    ibe.decrypt(&vet_key).map_err(|e| e.to_string())
}

/// Runs `f`, converting panics and errors into a thrown Java exception.
///
/// Returns the JNI null on failure so callers can `return` straight through. Fallible JNI
/// calls inside map to strings first — there is no message-carrying JNI error variant, and
/// the Java side only ever sees the thrown text.
fn guard<'local>(
    env: &mut JNIEnv<'local>,
    f: impl FnOnce(&mut JNIEnv<'local>) -> Result<jbyteArray, String> + std::panic::UnwindSafe,
) -> jbyteArray {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "vetkeys internal failure");
            std::ptr::null_mut()
        }
    }
}

fn guard_array<'local>(
    env: &mut JNIEnv<'local>,
    f: impl FnOnce(&mut JNIEnv<'local>) -> Result<jobjectArray, String> + std::panic::UnwindSafe,
) -> jobjectArray {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "vetkeys internal failure");
            std::ptr::null_mut()
        }
    }
}

/// Fallible JNI calls map to strings: the Java side only ever sees thrown text.
trait IntoStringError<T> {
    fn or_throw(self, what: &str) -> Result<T, String>;
}

impl<T> IntoStringError<T> for jni::errors::Result<T> {
    fn or_throw(self, what: &str) -> Result<T, String> {
        self.map_err(|e| format!("vetkeys JNI error during {what}: {e}"))
    }
}

fn throw(env: &mut JNIEnv, message: &str) {
    // Error strings here come from vetkeys' static messages plus our own decode failures —
    // never key material — so they are safe to surface for diagnostics.
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn to_byte_array<'a>(env: &'a JNIEnv<'a>, bytes: &[u8]) -> jni::errors::Result<JByteArray<'a>> {
    let arr = env.new_byte_array(bytes.len() as i32)?;
    env.set_byte_array_region(&arr, 0, unsafe {
        std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len())
    })?;
    Ok(arr)
}

fn from_byte_array(env: &mut JNIEnv, arr: &JByteArray) -> jni::errors::Result<Vec<u8>> {
    let len = env.get_array_length(arr)? as usize;
    let mut buf = vec![0i8; len];
    env.get_byte_array_region(arr, 0, &mut buf)?;
    Ok(buf.into_iter().map(|b| b as u8).collect())
}

/// `VetKeysNative.nativeTransportKeypair`: `[transportPublicKey, transportSecret]`.
#[no_mangle]
pub extern "system" fn Java_haven_mobile_core_haven_aol_vetkeys_VetKeysNative_nativeTransportKeypair<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> jobjectArray {
    guard_array(&mut env, |env| {
        let (public, secret) = transport_keypair()?;
        let out = env
            .new_object_array(2, "[B", JObject::null())
            .or_throw("allocating keypair array")?;
        env.set_object_array_element(&out, 0, to_byte_array(env, &public).or_throw("copying public key")?)
            .or_throw("storing public key")?;
        env.set_object_array_element(&out, 1, to_byte_array(env, &secret).or_throw("copying secret")?)
            .or_throw("storing secret")?;
        Ok(out.into_raw())
    })
}

/// `VetKeysNative.nativeUnwrapContentKey`: sealed record + canister outputs -> AES key.
#[no_mangle]
pub extern "system" fn Java_haven_mobile_core_haven_aol_vetkeys_VetKeysNative_nativeUnwrapContentKey<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    enc_vet_key: JByteArray<'local>,
    transport_secret: JByteArray<'local>,
    verification_key: JByteArray<'local>,
    derivation_input: JByteArray<'local>,
    encrypted_aes_key_b64: JByteArray<'local>,
) -> jbyteArray {
    guard(&mut env, |env| {
        let enc_vet_key = from_byte_array(env, &enc_vet_key).or_throw("reading encrypted key")?;
        let mut secret = from_byte_array(env, &transport_secret).or_throw("reading transport secret")?;
        let verification_key =
            from_byte_array(env, &verification_key).or_throw("reading verification key")?;
        let derivation_input =
            from_byte_array(env, &derivation_input).or_throw("reading derivation input")?;
        let sealed_b64 =
            from_byte_array(env, &encrypted_aes_key_b64).or_throw("reading sealed key")?;
        let aes = unwrap_content_key(
            &enc_vet_key,
            &secret,
            &verification_key,
            &derivation_input,
            &sealed_b64,
        )?;
        // The secret's JVM copy is zeroed Kotlin-side; wipe the native copy too.
        secret.zeroize();
        Ok(to_byte_array(env, &aes).or_throw("returning AES key")?.into_raw())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transport_keypair_shape_and_validity() {
        let (public, secret) = transport_keypair().expect("keypair must generate");
        assert_eq!(public.len(), 48, "G1-compressed transport public key");
        assert_eq!(secret.len(), 32, "scalar transport secret");
        assert!(is_valid_transport_public_key_encoding(&public));
        // The secret round-trips through its own encoding.
        let parsed = TransportSecretKey::deserialize(&secret).expect("secret must parse");
        assert_eq!(parsed.public_key(), public, "same keypair, same public key");
    }

    #[test]
    fn transport_keypairs_differ() {
        let (pub_a, sec_a) = transport_keypair().expect("first keypair");
        let (pub_b, sec_b) = transport_keypair().expect("second keypair");
        assert_ne!(pub_a, pub_b);
        assert_ne!(sec_a, sec_b);
    }

    #[test]
    fn unwrap_rejects_garbage_at_every_stage_without_panicking() {
        let good32 = vec![7u8; 32];
        let good48 = vec![7u8; 48];
        // Each input malformed in turn; the rest well-formed-but-bogus.
        let cases: Vec<(&[u8], &[u8], &[u8], &[u8], &[u8])> = vec![
            (b"nope", &good32, &good48, &good32, b"QUJD"),
            (&good48, b"nope", &good48, &good32, b"QUJD"),
            (&good48, &good32, b"nope", &good32, b"QUJD"),
            (&good48, &good32, &good48, &good32, b"!!!not-base64!!!"),
            (&good48, &good32, &good48, &good32, b""),
        ];
        for (enc, sec, vkey, input, sealed) in cases {
            assert!(
                unwrap_content_key(enc, sec, vkey, input, sealed).is_err(),
                "garbage must fail closed"
            );
        }
    }

    #[test]
    fn unwrap_rejects_empty_inputs() {
        assert!(unwrap_content_key(&[], &[], &[], &[], &[]).is_err());
    }
}
