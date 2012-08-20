/*************************************************************************
 *                                                                       *
 *  CESeCore: CE Security Core                                           *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.cesecore.certificates.ocsp;

import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.Callable;

import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.cesecore.certificates.ocsp.exception.OcspFailureException;

/**
 * This internal class exists for the sole purpose of catching deadlocks in the HSM hardware.
 * 
 * @version $Id$
 * 
 */
public class HsmResponseThread implements Callable<BasicOCSPResp> {

    public static final long HSM_TIMEOUT_SECONDS = 30; // in seconds

    private final BasicOCSPRespBuilder basicRes;
    private final String signingAlgorithm;
    private final PrivateKey signerKey;
    private final X509Certificate[] chain;
    private final String provider;


    public HsmResponseThread(final BasicOCSPRespBuilder basicRes, final String signingAlgorithm, final PrivateKey signerKey,
            final X509Certificate[] chain, final String provider) {
        this.basicRes = basicRes;
        this.signingAlgorithm = signingAlgorithm;
        this.signerKey = signerKey;
        this.chain = chain;
        this.provider = provider;
    }

    @Override
    public BasicOCSPResp call() throws NoSuchProviderException, OCSPException {
        try {
        final ContentSigner signer = new JcaContentSignerBuilder(signingAlgorithm).setProvider(provider).build(signerKey);
        JcaX509CertificateHolder[] jcaChain = new JcaX509CertificateHolder[chain.length];
        for(int i = 0; i < chain.length; ++i) {
            jcaChain[i] = new JcaX509CertificateHolder(chain[i]);
        }
        
        return basicRes.build(signer, jcaChain, new Date());
        } catch(CertificateEncodingException e) {
            throw new OcspFailureException(e);
        } catch (OperatorCreationException e) {
            throw new OcspFailureException(e);
        }
    }
}