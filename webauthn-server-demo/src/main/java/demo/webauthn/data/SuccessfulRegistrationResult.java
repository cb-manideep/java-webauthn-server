/*
 * Copyright (c) 2024 Chargebee Inc
 * All Rights Reserved.
 */
package demo.webauthn.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.yubico.webauthn.data.AuthenticatorData;
import com.yubico.webauthn.data.ByteArray;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * @author Manideep
 */
@Value
public class SuccessfulRegistrationResult {

    private static final Logger logger = LoggerFactory.getLogger(SuccessfulRegistrationResult.class);

    public boolean success = true;
    public RegistrationRequest request;
    public RegistrationResponse response;
    public CredentialRegistration registration;
    public boolean attestationTrusted;
    public Optional<AttestationCertInfo> attestationCert;

    @JsonSerialize(using = AuthDataSerializer.class)
    AuthenticatorData authData;

    String username;
    ByteArray sessionToken;

    public SuccessfulRegistrationResult(
            RegistrationRequest request, RegistrationResponse response, CredentialRegistration registration,
            boolean attestationTrusted, ByteArray sessionToken
    ) {
        this.request = request;
        this.response = response;
        this.registration = registration;
        this.attestationTrusted = attestationTrusted;
        attestationCert = Optional.ofNullable(
                response.getCredential().getResponse().getAttestation().getAttestationStatement().get("x5c")
        ).map(certs -> certs.get(0)).flatMap((JsonNode certDer) -> {
            try {
                return Optional.of(new ByteArray(certDer.binaryValue()));
            } catch (IOException e) {
                logger.error("Failed to get binary value from x5c element: {}", certDer, e);
                return Optional.empty();
            }
        }).map(AttestationCertInfo::new);
        this.authData = response.getCredential().getResponse().getParsedAuthenticatorData();
        this.username = request.getUsername();
        this.sessionToken = sessionToken;
    }
}
