package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrvMfiAssetsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun validProvisioningAtomicallyRotatesInstalledIdentity() {
        val first = identity(temporary.newFolder("first"))
        val second = identity(temporary.newFolder("second"))
        val target = File(temporary.root, "private/offline-mfi")

        installValidated(first, target)
        val firstCertificate = LocalMfiAuthenticationClient.load(target).readCertificate()
        installValidated(second, target)
        val secondCertificate = LocalMfiAuthenticationClient.load(target).readCertificate()

        assertFalse(firstCertificate.contentEquals(secondCertificate))
        assertArrayEquals(
            LocalMfiAuthenticationClient.load(second).readCertificate(),
            secondCertificate,
        )
    }

    @Test fun invalidUpdatePreservesInstalledIdentity() {
        val installed = identity(temporary.newFolder("installed"))
        val invalid = identity(temporary.newFolder("invalid"))
        val unrelatedKey = keyPair()
        File(invalid, "identity.pk8").writeBytes(unrelatedKey.private.encoded)
        val target = File(temporary.root, "private/offline-mfi")
        installValidated(installed, target)
        val expected = LocalMfiAuthenticationClient.load(target).readCertificate()

        assertThrows(Exception::class.java) { installValidated(invalid, target) }
        assertArrayEquals(expected, LocalMfiAuthenticationClient.load(target).readCertificate())
    }

    private fun installValidated(source: File, target: File) {
        val method = CrvMfiAssets::class.java.getDeclaredMethod(
            "installValidated",
            File::class.java,
            File::class.java,
        ).apply { isAccessible = true }
        try {
            method.invoke(CrvMfiAssets, source, target)
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }

    private fun identity(directory: File): File {
        val pair = keyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=CR-V synthetic MFi test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.valueOf(System.nanoTime())))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4_102_444_800_000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(tbs.encoded)
        val certificate = DERSequence(
            arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign())),
        ).encoded
        File(directory, "identity.pk8").writeBytes(pair.private.encoded)
        File(directory, "certificate.p7b").writeBytes(certificate)
        return directory
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
}
