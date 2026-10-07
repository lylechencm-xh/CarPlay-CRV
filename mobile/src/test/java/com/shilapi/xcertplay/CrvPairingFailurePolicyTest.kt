package com.shilapi.xcertplay

import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrvPairingFailurePolicyTest {
    @Test fun missingPkixDoesNotErasePairing() {
        assertFalse(CrvPairingFailurePolicy.isPairRejection(
            NoSuchAlgorithmException("KeyManagerFactory PKIX implementation not found"),
        ))
        assertFalse(CrvPairingFailurePolicy.isPairRejection(
            RuntimeException("TLS failed", GeneralSecurityException("Invalid PKCS#8 private key PEM")),
        ))
    }

    @Test fun explicitPhoneRejectionAllowsRePairing() {
        for (code in listOf("InvalidHostID", "InvalidPairRecord")) {
            assertTrue(CrvPairingFailurePolicy.isPairRejection(
                RuntimeException("connection failed", RuntimeException("Lockdown StartSession failed: $code")),
            ))
        }
    }

    @Test fun unrelatedErrorsPreservePairing() {
        assertFalse(CrvPairingFailurePolicy.isPairRejection(RuntimeException("HostID read timed out")))
        assertFalse(CrvPairingFailurePolicy.isPairRejection(RuntimeException("Lockdown StartSession failed: PasswordProtected")))
    }
}
