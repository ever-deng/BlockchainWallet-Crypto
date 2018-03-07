/*
 * Copyright 2013 bits of proof zrt.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.quincysx.crypto;

import com.quincysx.crypto.bip32.ValidationException;
import com.quincysx.crypto.ethereum.ECDSASignature;
import com.quincysx.crypto.utils.Base58;
import com.quincysx.crypto.utils.RIPEMD160;
import com.quincysx.crypto.utils.SHA256;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;

import org.spongycastle.asn1.ASN1InputStream;
import org.spongycastle.asn1.DERInteger;
import org.spongycastle.asn1.DERSequenceGenerator;
import org.spongycastle.asn1.DLSequence;
import org.spongycastle.asn1.sec.SECNamedCurves;
import org.spongycastle.asn1.x9.X9ECParameters;
import org.spongycastle.asn1.x9.X9IntegerConverter;
import org.spongycastle.crypto.AsymmetricCipherKeyPair;
import org.spongycastle.crypto.digests.SHA256Digest;
import org.spongycastle.crypto.generators.ECKeyPairGenerator;
import org.spongycastle.crypto.params.ECDomainParameters;
import org.spongycastle.crypto.params.ECKeyGenerationParameters;
import org.spongycastle.crypto.params.ECPrivateKeyParameters;
import org.spongycastle.crypto.params.ECPublicKeyParameters;
import org.spongycastle.crypto.signers.ECDSASigner;
import org.spongycastle.crypto.signers.HMacDSAKCalculator;
import org.spongycastle.math.ec.ECAlgorithms;
import org.spongycastle.math.ec.ECCurve;
import org.spongycastle.math.ec.ECPoint;
import org.spongycastle.util.Arrays;


public class ECKeyPair implements Key {
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters domain = new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());

    private BigInteger priv;
    private byte[] pub;
    private byte[] pubNoCompressed;
    private boolean compressed;

    private ECKeyPair() {
    }

    @Override
    public boolean isCompressed() {
        return compressed;
    }

    @Override
    public ECKeyPair clone() throws CloneNotSupportedException {
        ECKeyPair c = (ECKeyPair) super.clone();
        c.priv = new BigInteger(c.priv.toByteArray());
        c.pub = Arrays.clone(pub);
        c.compressed = compressed;
        return c;
    }

    public static ECKeyPair createNew(boolean compressed) {
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        ECKeyGenerationParameters keygenParams = new ECKeyGenerationParameters(domain, secureRandom);
        generator.init(keygenParams);
        AsymmetricCipherKeyPair keypair = generator.generateKeyPair();
        ECPrivateKeyParameters privParams = (ECPrivateKeyParameters) keypair.getPrivate();
        ECPublicKeyParameters pubParams = (ECPublicKeyParameters) keypair.getPublic();
        ECKeyPair k = new ECKeyPair();
        k.priv = privParams.getD();
        k.compressed = compressed;
        if (compressed) {
            ECPoint q = pubParams.getQ();
            k.pub = new ECPoint.Fp(domain.getCurve(), q.getX(), q.getY(), true).getEncoded();
        } else {
            k.pub = pubParams.getQ().getEncoded();
        }
        return k;
    }

    public void setPublic(byte[] pub) throws ValidationException {
        throw new ValidationException("Can not set public key if private is present");
    }

    @Override
    public byte[] getPrivate() {
        byte[] p = priv.toByteArray();

        if (p.length != 32) {
            byte[] tmp = new byte[32];
            System.arraycopy(p, Math.max(0, p.length - 32), tmp, Math.max(0, 32 - p.length), Math.min(32, p.length));
            p = tmp;
        }

        return p;
    }

    @Override
    public byte[] getPublic() {
        return Arrays.clone(pub);
    }

    @Override
    public byte[] getAddress() {
        return RIPEMD160.hash160(pub);
    }

    public ECKeyPair(byte[] p, boolean compressed) throws ValidationException {
        if (p.length != 32) {
            throw new ValidationException("Invalid private key");
        }
        this.priv = new BigInteger(1, p).mod(CURVE.getN());
        this.compressed = compressed;
        pubNoCompressed = pub = CURVE.getG().multiply(priv).getEncoded();
        if (compressed) {
            ECPoint q = CURVE.getG().multiply(priv);
            pub = new ECPoint.Fp(domain.getCurve(), q.getX(), q.getY(), true).getEncoded();
        } else {
            pub = CURVE.getG().multiply(priv).getEncoded();
        }
    }

    public ECKeyPair(BigInteger priv, boolean compressed) {
        this.priv = priv;
        this.compressed = compressed;
        pubNoCompressed = pub = CURVE.getG().multiply(priv).getEncoded();
        if (compressed) {
            ECPoint q = CURVE.getG().multiply(priv);
            pub = new ECPoint.Fp(domain.getCurve(), q.getX(), q.getY(), true).getEncoded();
        } else {
            pub = CURVE.getG().multiply(priv).getEncoded();
        }
    }

    public byte[] signBTC(byte[] hash) throws ValidationException {
        if (priv == null) {
            throw new ValidationException("Need private key to sign");
        }
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ECPrivateKeyParameters(priv, domain));
        BigInteger[] signature = signer.generateSignature(hash);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        try {
            DERSequenceGenerator seq = new DERSequenceGenerator(s);
            seq.addObject(new DERInteger(signature[0]));
            seq.addObject(new DERInteger(signature[1]));
            seq.close();
            return s.toByteArray();
        } catch (IOException e) {
        }
        return null;
    }

    public boolean verifyBTC(byte[] hash, byte[] signature) {
        return verify(hash, signature, pub);
    }


    public static boolean verify(byte[] hash, byte[] signature, byte[] pub) {
        ASN1InputStream asn1 = new ASN1InputStream(signature);
        try {
            ECDSASigner signer = new ECDSASigner();
            signer.init(false, new ECPublicKeyParameters(CURVE.getCurve().decodePoint(pub), domain));

            DLSequence seq = (DLSequence) asn1.readObject();
            BigInteger r = ((DERInteger) seq.getObjectAt(0)).getPositiveValue();
            BigInteger s = ((DERInteger) seq.getObjectAt(1)).getPositiveValue();
            return signer.verifySignature(hash, r, s);
        } catch (Exception e) {
            // threat format errors as invalid signatures
            return false;
        } finally {
            try {
                asn1.close();
            } catch (IOException e) {
            }
        }
    }

    public static String serializeWIF(Key key) {
        return Base58.encode(bytesWIF(key));
    }

    private static byte[] bytesWIF(Key key) {
        byte[] k = key.getPrivate();
        if (key.isCompressed()) {
            byte[] encoded = new byte[k.length + 6];
            byte[] ek = new byte[k.length + 2];
            ek[0] = (byte) 0x80;
            System.arraycopy(k, 0, ek, 1, k.length);
            ek[k.length + 1] = 0x01;
            byte[] hash = SHA256.doubleSha256(ek);
            System.arraycopy(ek, 0, encoded, 0, ek.length);
            System.arraycopy(hash, 0, encoded, ek.length, 4);
            return encoded;
        } else {
            byte[] encoded = new byte[k.length + 5];
            byte[] ek = new byte[k.length + 1];
            ek[0] = (byte) 0x80;
            System.arraycopy(k, 0, ek, 1, k.length);
            byte[] hash = SHA256.doubleSha256(ek);
            System.arraycopy(ek, 0, encoded, 0, ek.length);
            System.arraycopy(hash, 0, encoded, ek.length, 4);
            return encoded;
        }
    }

    public static ECKeyPair parseWIF(String serialized) throws ValidationException {
        byte[] store = Base58.decode(serialized);
        return parseBytesWIF(store);
    }

    public static ECKeyPair parseBytesWIF(byte[] store) throws ValidationException {
        if (store.length == 37) {
            checkChecksum(store);
            byte[] key = new byte[store.length - 5];
            System.arraycopy(store, 1, key, 0, store.length - 5);
            return new ECKeyPair(key, false);
        } else if (store.length == 38) {
            checkChecksum(store);
            byte[] key = new byte[store.length - 6];
            System.arraycopy(store, 1, key, 0, store.length - 6);
            return new ECKeyPair(key, true);
        }
        throw new ValidationException("Invalid key length");
    }

    private static void checkChecksum(byte[] store) throws ValidationException {
        byte[] checksum = new byte[4];
        System.arraycopy(store, store.length - 4, checksum, 0, 4);
        byte[] ekey = new byte[store.length - 4];
        System.arraycopy(store, 0, ekey, 0, store.length - 4);
        byte[] hash = SHA256.doubleSha256(ekey);
        for (int i = 0; i < 4; ++i) {
            if (hash[i] != checksum[i]) {
                throw new ValidationException("checksum mismatch");
            }
        }
    }

    public ECDSASignature doSign(byte[] input) {
        if (input.length != 32) {
            throw new IllegalArgumentException("Expected 32 byte input to ECDSA signature, not " + input.length);
        }
        // No decryption of private key required.
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        ECPrivateKeyParameters privKeyParams = new ECPrivateKeyParameters(priv, domain);
        signer.init(true, privKeyParams);
        BigInteger[] components = signer.generateSignature(input);
        return new ECDSASignature(components[0], components[1]).toCanonicalised();
    }

    public ECDSASignature sign(byte[] messageHash) {
        ECDSASignature sig = doSign(messageHash);
        // Now we have to work backwards to figure out the recId needed to recover the signature.
        int recId = -1;
        for (int i = 0; i < 4; i++) {
            byte[] k = recoverPubBytesFromSignature(i, sig, messageHash);
            if (k != null && java.util.Arrays.equals(k, pubNoCompressed)) {
                recId = i;
                break;
            }
        }
        if (recId == -1)
            throw new RuntimeException("Could not construct a recoverable key. This should never happen.");
        sig.v = (byte) (recId + 27);
        return sig;
    }

    public static byte[] recoverPubBytesFromSignature(int recId, ECDSASignature sig, byte[] messageHash) {
        check(recId >= 0, "recId must be positive");
        check(sig.r.signum() >= 0, "r must be positive");
        check(sig.s.signum() >= 0, "s must be positive");
        check(messageHash != null, "messageHash must not be null");
        // 1.0 For j from 0 to h   (h == recId here and the loop is outside this function)
        //   1.1 Let x = r + jn
        BigInteger n = CURVE.getN();  // Curve order.
        BigInteger i = BigInteger.valueOf((long) recId / 2);
        BigInteger x = sig.r.add(i.multiply(n));
        //   1.2. Convert the integer x to an octet string X of length mlen using the conversion routine
        //        specified in Section 2.3.7, where mlen = ⌈(log2 p)/8⌉ or mlen = ⌈m/8⌉.
        //   1.3. Convert the octet string (16 set binary digits)||X to an elliptic curve point R using the
        //        conversion routine specified in Section 2.3.4. If this conversion routine outputs “invalid”, then
        //        do another iteration of Step 1.
        //
        // More concisely, what these points mean is to use X as a compressed public key.
        ECCurve.Fp curve = (ECCurve.Fp) CURVE.getCurve();
        BigInteger prime = curve.getQ();  // Bouncy Castle is not consistent about the letter it uses for the prime.
        if (x.compareTo(prime) >= 0) {
            // Cannot have point co-ordinates larger than this as everything takes place modulo Q.
            return null;
        }
        // Compressed keys require you to know an extra bit of data about the y-coord as there are two possibilities.
        // So it's encoded in the recId.
        ECPoint R = decompressKey(x, (recId & 1) == 1);
        //   1.4. If nR != point at infinity, then do another iteration of Step 1 (callers responsibility).
        if (!R.multiply(n).isInfinity())
            return null;
        //   1.5. Compute e from M using Steps 2 and 3 of ECDSA signature verification.
        BigInteger e = new BigInteger(1, messageHash);
        //   1.6. For k from 1 to 2 do the following.   (loop is outside this function via iterating recId)
        //   1.6.1. Compute a candidate public key as:
        //               Q = mi(r) * (sR - eG)
        //
        // Where mi(x) is the modular multiplicative inverse. We transform this into the following:
        //               Q = (mi(r) * s ** R) + (mi(r) * -e ** G)
        // Where -e is the modular additive inverse of e, that is z such that z + e = 0 (mod n). In the above equation
        // ** is point multiplication and + is point addition (the EC group operator).
        //
        // We can find the additive inverse by subtracting e from zero then taking the mod. For example the additive
        // inverse of 3 modulo 11 is 8 because 3 + 8 mod 11 = 0, and -3 mod 11 = 8.
        BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
        BigInteger rInv = sig.r.modInverse(n);
        BigInteger srInv = rInv.multiply(sig.s).mod(n);
        BigInteger eInvrInv = rInv.multiply(eInv).mod(n);
        ECPoint.Fp q = (ECPoint.Fp) ECAlgorithms.sumOfTwoMultiplies(CURVE.getG(), eInvrInv, R, srInv);
        return q.getEncoded(/* compressed */ false);
    }

    private static ECPoint decompressKey(BigInteger xBN, boolean yBit) {
        X9IntegerConverter x9 = new X9IntegerConverter();
        byte[] compEnc = x9.integerToBytes(xBN, 1 + x9.getByteLength(CURVE.getCurve()));
        compEnc[0] = (byte) (yBit ? 0x03 : 0x02);
        return CURVE.getCurve().decodePoint(compEnc);
    }

    private static void check(boolean test, String message) {
        if (!test) throw new IllegalArgumentException(message);
    }
}