/*
 * Copyright (c) 2024 Chargebee Inc
 * All Rights Reserved.
 */
package demo.webauthn.data;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.yubico.webauthn.data.AuthenticatorData;
import com.yubico.webauthn.data.ByteArray;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.Collection;

/**
 * @author Manideep
 */
@Value
@AllArgsConstructor
public class SuccessfulAuthenticationResult {
    boolean success = true;
    AssertionRequestWrapper request;
    AssertionResponse response;
    Collection<CredentialRegistration> registrations;

    @JsonSerialize(using = AuthDataSerializer.class)
    AuthenticatorData authData;

    String username;
    ByteArray sessionToken;

    public SuccessfulAuthenticationResult(
            AssertionRequestWrapper request, AssertionResponse response,
            Collection<CredentialRegistration> registrations, String username, ByteArray sessionToken
    ) {
        this(request, response, registrations, response.getCredential().getResponse().getParsedAuthenticatorData(), username, sessionToken);
    }
}
