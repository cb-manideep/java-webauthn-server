/*
 * Copyright (c) 2024 Chargebee Inc
 * All Rights Reserved.
 */
package demo.webauthn.data;

import com.yubico.internal.util.CertificateParser;
import com.yubico.webauthn.data.ByteArray;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * @author Manideep
 */
@Value
public class AttestationCertInfo {
    
    private static final Logger logger = LoggerFactory.getLogger(AttestationCertInfo.class);
    
    ByteArray der;
    String text;

    public AttestationCertInfo(ByteArray certDer) {
        der = certDer;
        X509Certificate cert = null;
        try {
            cert = CertificateParser.parseDer(certDer.getBytes());
        } catch (CertificateException e) {
            logger.error("Failed to parse attestation certificate");
        }
        if (cert == null) {
            text = null;
        } else {
            text = cert.toString();
        }
    }
}
