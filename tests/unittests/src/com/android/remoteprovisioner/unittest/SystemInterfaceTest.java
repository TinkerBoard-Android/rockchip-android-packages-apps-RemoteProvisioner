/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.remoteprovisioner.unittest;

import static android.security.keystore.KeyProperties.KEY_ALGORITHM_EC;
import static android.security.keystore.KeyProperties.PURPOSE_SIGN;

import static com.android.remoteprovisioner.unittest.Utils.generateEcdsaKeyPair;
import static com.android.remoteprovisioner.unittest.Utils.getP256PubKeyFromBytes;
import static com.android.remoteprovisioner.unittest.Utils.signPublicKey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.SecurityLevel;
import android.os.ServiceManager;
import android.platform.test.annotations.Presubmit;
import android.security.keystore.KeyGenParameterSpec;
import android.security.remoteprovisioning.IRemoteProvisioning;

import androidx.test.runner.AndroidJUnit4;

import com.android.remoteprovisioner.GeekResponse;
import com.android.remoteprovisioner.SystemInterface;
import com.android.remoteprovisioner.X509Utils;

import com.google.crypto.tink.subtle.Hkdf;
import com.google.crypto.tink.subtle.X25519;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.UnsignedInteger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@RunWith(AndroidJUnit4.class)
public class SystemInterfaceTest {

    private static final String SERVICE = "android.security.remoteprovisioning";

    private IRemoteProvisioning mBinder;

    @Before
    public void setUp() throws Exception {
        mBinder =
              IRemoteProvisioning.Stub.asInterface(ServiceManager.getService(SERVICE));
        assertNotNull(mBinder);
    }

    private static byte[] serializeProtectedHeaders() throws CborException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder()
                .addMap()
                    .put(1, -8) // Algorithm, EdDSA
                    .end()
                .build());
        return baos.toByteArray();
    }

    private static byte[] buildSignatureKey() throws CborException {
        byte[] key = new byte[32];
        new Random().nextBytes(key);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder()
                .addMap()
                    .put(1, 1) // Key type, OKP
                    .put(3, -8) // Algorithm, EdDSA
                    .put(-1, 6) // Curve, Ed25519
                    .put(-2, key) // public key, bytes
                    .end()
                .build());
        return baos.toByteArray();
    }

    private static Array buildSignedSignatureKey() throws CborException {
        return (Array) (new CborBuilder()
                .addArray()
                    .add(serializeProtectedHeaders())
                    .add(new byte[0]) //unprotected headers. This should be an empty map
                    .add(buildSignatureKey())
                    //signature; test mode will instruct the HAL component to skip verification
                    .add(new byte[0])
                    .end()
                .build().get(0));
    }

    private static byte[] buildEek(byte[] eek) throws CborException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .addMap()
                        .put(1, 1) // Key type, OKP
                        .put(2, digest.digest(eek)) // KID: EEK ID
                        .put(3, -25) // Algorithm
                        .put(-1, 4) // Curve, X25519
                        .put(-2, eek) // public key, bytes
                        .end()
                    .build());
            return baos.toByteArray();
        } catch (NoSuchAlgorithmException e) {
            fail("SHA-256 somehow not available");
            return null;
        }
    }

    private static Array buildSignedEek(byte[] eek) throws CborException {
        return (Array) (new CborBuilder()
                .addArray()
                    .add(serializeProtectedHeaders())
                    .add(new byte[0])
                    .add(buildEek(eek))
                    .add(new byte[0]) //signature; test mode skips this
                    .end()
                .build().get(0));
    }

    private byte[] generateEekChain(byte[] eek) throws CborException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder()
                .addArray()
                    .add(buildSignedSignatureKey())
                    .add(buildSignedEek(eek))
                    .end()
                .build());
        return baos.toByteArray();
    }

    @Presubmit
    @Test
    public void testGenerateCSR() throws Exception {
        ProtectedData encryptedBundle = new ProtectedData();
        byte[] eek = new byte[32];
        new Random().nextBytes(eek);
        GeekResponse geek = new GeekResponse(generateEekChain(eek), new byte[] {0x02});
        byte[] bundle =
            SystemInterface.generateCsr(true /* testMode */, 0 /* numKeys */,
                                        SecurityLevel.TRUSTED_ENVIRONMENT,
                                        geek, encryptedBundle, mBinder);
        // encryptedBundle should contain a COSE_Encrypt message
        ByteArrayInputStream bais = new ByteArrayInputStream(encryptedBundle.protectedData);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertEquals(MajorType.ARRAY, dataItems.get(0).getMajorType());
        Array encMsg = (Array) dataItems.get(0);
        assertEquals(4, encMsg.getDataItems().size());
    }

    private static Certificate[] generateKeyStoreKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM_EC,
                "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN)
                .setAttestationChallenge("challenge".getBytes())
                .build();
        keyPairGenerator.initialize(spec);
        keyPairGenerator.generateKeyPair();
        Certificate[] certs = keyStore.getCertificateChain(spec.getKeystoreAlias());
        keyStore.deleteEntry(alias);
        return certs;
    }

    @Presubmit
    @Test
    public void testGenerateCSRProvisionAndUseKey() throws Exception {
        ProtectedData encryptedBundle = new ProtectedData();
        int numKeys = 1;
        byte[] eek = new byte[32];
        new Random().nextBytes(eek);
        GeekResponse geek = new GeekResponse(generateEekChain(eek), new byte[] {0x02});
        mBinder.generateKeyPair(true /* testMode */, SecurityLevel.TRUSTED_ENVIRONMENT);
        byte[] bundle =
            SystemInterface.generateCsr(true /* testMode */, numKeys,
                                        SecurityLevel.TRUSTED_ENVIRONMENT,
                                        geek, encryptedBundle, mBinder);
        assertNotNull(bundle);
        // The return value of generateCsr should be a COSE_Mac0 message
        ByteArrayInputStream bais = new ByteArrayInputStream(bundle);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertEquals(MajorType.ARRAY, dataItems.get(0).getMajorType());
        Array macMsg = (Array) dataItems.get(0);
        assertEquals(4, macMsg.getDataItems().size());

        // The payload for the COSE_Mac0 should contain the array of public keys
        bais = new ByteArrayInputStream(((ByteString) macMsg.getDataItems().get(2)).getBytes());
        List<DataItem> publicKeysArr = new CborDecoder(bais).decode();
        assertEquals(1, publicKeysArr.size());
        assertEquals(MajorType.ARRAY, publicKeysArr.get(0).getMajorType());
        Array publicKeys = (Array) publicKeysArr.get(0);
        assertEquals(numKeys, publicKeys.getDataItems().size());
        Map publicKey = (Map) publicKeys.getDataItems().get(0);
        byte[] xPub = ((ByteString) publicKey.get(new NegativeInteger(-2))).getBytes();
        byte[] yPub = ((ByteString) publicKey.get(new NegativeInteger(-3))).getBytes();
        assertEquals(xPub.length, 32);
        assertEquals(yPub.length, 32);
        KeyPair rootKeyPair = generateEcdsaKeyPair();
        KeyPair intermediateKeyPair = generateEcdsaKeyPair();
        PublicKey leafKeyToSign = getP256PubKeyFromBytes(xPub, yPub);
        X509Certificate[] certChain = new X509Certificate[3];
        certChain[0] = signPublicKey(intermediateKeyPair, leafKeyToSign);
        certChain[1] = signPublicKey(rootKeyPair, intermediateKeyPair.getPublic());
        certChain[2] = signPublicKey(rootKeyPair, rootKeyPair.getPublic());
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        for (int i = 0; i < certChain.length; i++) {
            os.write(certChain[i].getEncoded());
        }
        SystemInterface.provisionCertChain(X509Utils.getAndFormatRawPublicKey(certChain[0]),
                                           certChain[0].getEncoded(),
                                           os.toByteArray(),
                                           System.currentTimeMillis() + 2000, // Valid for 2 seconds
                                           SecurityLevel.TRUSTED_ENVIRONMENT,
                                           mBinder);
        // getPoolStatus will clean the key pool before we go to assign a new provisioned key
        mBinder.getPoolStatus(0, SecurityLevel.TRUSTED_ENVIRONMENT);
        Certificate[] provisionedCerts1 = generateKeyStoreKey("alias");
        Certificate[] provisionedCerts2 = generateKeyStoreKey("alias2");
        assertEquals(4, provisionedCerts1.length);
        assertEquals(4, provisionedCerts2.length);
        for (int i = 0; i < certChain.length; i++) {
            assertArrayEquals("i = " + i,
                    provisionedCerts1[i + 1].getEncoded(), certChain[i].getEncoded());
            assertArrayEquals("i = " + i,
                    provisionedCerts2[i + 1].getEncoded(), certChain[i].getEncoded());
        }
    }

    private static byte[] extractRecipientKey(Array recipients) {
        // Recipients is an Array of recipient Arrays
        Map recipientUnprotectedHeaders = (Map) ((Array) recipients.getDataItems().get(0))
                                                                   .getDataItems().get(1);
        Map recipientKeyMap = (Map) recipientUnprotectedHeaders.get(new NegativeInteger(-1));
        return ((ByteString) recipientKeyMap.get(new NegativeInteger(-2))).getBytes();
    }

    private static byte[] buildKdfContext(byte[] serverPub, byte[] ephemeralPub) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder()
                .addArray()
                    .add(3) // AlgorithmID: AES-GCM 256
                    .addArray()
                        .add("client".getBytes("UTF8"))
                        .add(new byte[0])
                        .add(ephemeralPub)
                        .end()
                    .addArray()
                        .add("server".getBytes("UTF8"))
                        .add(new byte[0])
                        .add(serverPub)
                        .end()
                    .addArray()
                        .add(128) // key length
                        .add(new byte[0])
                        .end()
                    .end()
                .build());
        return baos.toByteArray();
    }

    private static byte[] buildEncStructure(byte[] protectedHeaders, byte[] externalAad)
            throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(new CborBuilder()
                .addArray()
                    .add("Encrypt")
                    .add(protectedHeaders)
                    .add(externalAad)
                    .end()
                .build());
        return baos.toByteArray();
    }

    @Presubmit
    @Test
    public void testDecryptProtectedPayload() throws Exception {
        ProtectedData encryptedBundle = new ProtectedData();
        int numKeys = 1;
        byte[] eekPriv = X25519.generatePrivateKey();
        byte[] eekPub = X25519.publicFromPrivate(eekPriv);
        GeekResponse geek = new GeekResponse(generateEekChain(eekPub), new byte[] {0x02});
        mBinder.generateKeyPair(true /* testMode */, SecurityLevel.TRUSTED_ENVIRONMENT);
        byte[] bundle =
            SystemInterface.generateCsr(true /* testMode */, numKeys,
                                        SecurityLevel.TRUSTED_ENVIRONMENT,
                                        geek, encryptedBundle, mBinder);
        ByteArrayInputStream bais = new ByteArrayInputStream(encryptedBundle.protectedData);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        // Parse encMsg into components: protected and unprotected headers, payload, and recipient
        List<DataItem> encMsg = ((Array) dataItems.get(0)).getDataItems();
        byte[] protectedHeaders = ((ByteString) encMsg.get(0)).getBytes();
        Map unprotectedHeaders = (Map) encMsg.get(1);
        byte[] encryptedContent = ((ByteString) encMsg.get(2)).getBytes();
        Array recipients = (Array) encMsg.get(3);

        byte[] iv = ((ByteString) unprotectedHeaders.get(new UnsignedInteger(5))).getBytes();
        byte[] ephemeralPub = extractRecipientKey(recipients);
        assertEquals(32, ephemeralPub.length);
        byte[] sharedSecret = X25519.computeSharedSecret(eekPriv, ephemeralPub);
        byte[] context = buildKdfContext(eekPub, ephemeralPub);
        byte[] decryptionKey = Hkdf.computeHkdf("HMACSHA256", sharedSecret, null /* salt */,
                                                context, 32);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(decryptionKey, "AES"),
                new GCMParameterSpec(128 /* iv length */, iv));
        cipher.updateAAD(buildEncStructure(protectedHeaders, new byte[0]));

        byte[] protectedData = cipher.doFinal(encryptedContent);
        bais = new ByteArrayInputStream(protectedData);
        List<DataItem> protectedDataArray = new CborDecoder(bais).decode();
        assertEquals(1, protectedDataArray.size());
        assertEquals(MajorType.ARRAY, protectedDataArray.get(0).getMajorType());
        List<DataItem> protectedDataPayload = ((Array) protectedDataArray.get(0)).getDataItems();
        assertTrue(protectedDataPayload.size() == 2 || protectedDataPayload.size() == 3);
    }
}