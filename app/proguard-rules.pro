# JNI boundary: Rust exports are bound by class + method name.
-keep class haven.mobile.core.haven.aol.vetkeys.VetKeysNative { *; }

# web3j: EIP-712 parsing reflects over these with Jackson.
-keep class org.web3j.crypto.StructuredData** { *; }
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* <fields>; @com.fasterxml.jackson.annotation.* <methods>; }
# EIP-4844 blob code references the excluded KZG / tuweni libraries; never called here.
-dontwarn ethereum.ckzg4844.**
-dontwarn org.apache.tuweni.**
-dontwarn io.consensys.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
