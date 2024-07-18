/*
 * Copyright (c) 2024 Chargebee Inc
 * All Rights Reserved.
 */
package demo.webauthn.data;

import demo.webauthn.data.CredentialRegistration;
import lombok.Value;

/**
 * @author Manideep
 */
@Value
public class DeregisterCredentialResult {
    boolean success = true;
    CredentialRegistration droppedRegistration;
    boolean accountDeleted;
}
