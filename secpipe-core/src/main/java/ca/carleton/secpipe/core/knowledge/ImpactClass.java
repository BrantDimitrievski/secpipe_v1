package ca.carleton.secpipe.core.knowledge;

/** Coarse impact class of a CWE family; used by scoring and reports. */
public enum ImpactClass {
    INJECTION,
    FILE_ACCESS,
    CRYPTOGRAPHIC_WEAKNESS,
    CREDENTIAL_EXPOSURE,
    UNSAFE_DESERIALIZATION
}
