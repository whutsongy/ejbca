/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
package org.ejbca.ui.cli.ca;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.util.Arrays;

import org.cesecore.CaTestUtils;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.certificates.ca.CADoesntExistsException;
import org.cesecore.certificates.ca.CaSessionRemote;
import org.cesecore.certificates.ca.X509CA;
import org.cesecore.certificates.certificate.InternalCertificateStoreSessionRemote;
import org.cesecore.certificates.endentity.EndEntityInformation;
import org.cesecore.certificates.util.AlgorithmConstants;
import org.cesecore.keys.util.KeyTools;
import org.cesecore.mock.authentication.tokens.TestAlwaysAllowLocalAuthenticationToken;
import org.cesecore.util.CertTools;
import org.cesecore.util.CryptoProviderTools;
import org.cesecore.util.EjbRemoteHelper;
import org.cesecore.util.FileTools;
import org.ejbca.core.ejb.ra.EndEntityAccessSessionRemote;
import org.ejbca.core.ejb.ra.EndEntityManagementSessionRemote;
import org.ejbca.ui.cli.ErrorAdminCommandException;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Id$
 *
 */
public class CaImportCertCommandTest {

    private static final String CA_NAME = "CaImportCertCommandTest";
    private static final String CA_DN = "CN=" + CA_NAME;
    private static final String USERNAME = "CaImportCertCommandTest";
    private static final String CERTIFICATE_DN = "C=SE,O=foo,CN=" + USERNAME;

    private final AuthenticationToken authenticationToken = new TestAlwaysAllowLocalAuthenticationToken(CaImportCertCommandTest.class.getSimpleName());

    private final CaSessionRemote caSession = EjbRemoteHelper.INSTANCE.getRemoteSession(CaSessionRemote.class);
    private final EndEntityManagementSessionRemote endEntityManagementSession = EjbRemoteHelper.INSTANCE
            .getRemoteSession(EndEntityManagementSessionRemote.class);
    private final EndEntityAccessSessionRemote endEntityAccessSession = EjbRemoteHelper.INSTANCE.getRemoteSession(EndEntityAccessSessionRemote.class);
    private final InternalCertificateStoreSessionRemote internalCertificateStoreSession = EjbRemoteHelper.INSTANCE
            .getRemoteSession(InternalCertificateStoreSessionRemote.class);

    private CaImportCertCommand command = new CaImportCertCommand();
    private X509CA ca;
    private File certificateFile;

    @BeforeClass
    public static void beforeClass() throws Exception {
        CryptoProviderTools.installBCProviderIfNotAvailable();
    }

    @Before
    public void setup() throws Exception {
        //Creates a CA with AlgorithmConstants.SIGALG_SHA256_WITH_RSA
        ca = CaTestUtils.createTestX509CA(CA_DN, null, false);
        caSession.addCA(authenticationToken, ca);
        certificateFile = File.createTempFile("test", null);
        KeyPair keys = KeyTools.genKeys("512", AlgorithmConstants.KEYALGORITHM_RSA);
        Certificate certificate = CertTools.genSelfCert(CERTIFICATE_DN, 365, null, keys.getPrivate(), keys.getPublic(),
                AlgorithmConstants.SIGALG_SHA1_WITH_RSA, true);
        FileOutputStream fileOutputStream = new FileOutputStream(certificateFile);
        try {
            fileOutputStream.write(CertTools.getPemFromCertificateChain(Arrays.asList(certificate)));
        } finally {
            fileOutputStream.close();
        }
        //Delete any previous users
        EndEntityInformation previousUser = endEntityAccessSession.findUser(authenticationToken, USERNAME);
        if(previousUser != null) {
            endEntityManagementSession.deleteUser(authenticationToken, USERNAME);
        }
        
    }

    @After
    public void tearDown() throws Exception {
        if (ca != null) {
            caSession.removeCA(authenticationToken, ca.getCAId());
        }
        if (certificateFile.exists()) {
            FileTools.delete(certificateFile);
        }
        endEntityManagementSession.deleteUser(authenticationToken, USERNAME);
        internalCertificateStoreSession.removeCertificatesBySubject(CERTIFICATE_DN);
    }

    @Test
    public void testCommand() throws ErrorAdminCommandException, CADoesntExistsException, AuthorizationDeniedException {
        String[] args = new String[] { "importcert", USERNAME, "foo123", CA_NAME, "ACTIVE", "foo@foo.com", certificateFile.getAbsolutePath(),
                "EMPTY", "ENDUSER" };
        command.execute(args);
        assertNotNull("Certificate was not imported.", endEntityAccessSession.findUser(authenticationToken, USERNAME));
    }
}
