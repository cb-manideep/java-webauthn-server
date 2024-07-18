// Copyright (c) 2018, Yubico AB
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package demo.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yubico.fido.metadata.FidoMetadataDownloader;
import com.yubico.fido.metadata.FidoMetadataDownloaderException;
import com.yubico.fido.metadata.FidoMetadataService;
import com.yubico.fido.metadata.UnexpectedLegalHeader;
import com.yubico.internal.util.JacksonCodecs;
import com.yubico.util.Either;
import com.yubico.webauthn.*;
import com.yubico.webauthn.attestation.YubicoJsonMetadataService;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import demo.webauthn.data.*;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class WebAuthnServer {
    private static final Logger logger = LoggerFactory.getLogger(WebAuthnServer.class);
    private static final SecureRandom random = new SecureRandom();

    private final Cache<ByteArray, AssertionRequestWrapper> assertRequestStorage;
    private final Cache<ByteArray, RegistrationRequest> registerRequestStorage;
    private final InMemoryRegistrationStorage userStorage;
    private final SessionManager sessions = new SessionManager();

    private final MetadataService metadataService = getMetadataService();

    private static MetadataService getMetadataService() throws CertPathValidatorException, InvalidAlgorithmParameterException, Base64UrlException, DigestException, FidoMetadataDownloaderException, CertificateException, UnexpectedLegalHeader, IOException, NoSuchAlgorithmException, SignatureException, InvalidKeyException {
        if (Config.useFidoMds()) {
            logger.info("Using combination of Yubico JSON file and FIDO MDS for attestation metadata.");
            return new CompositeMetadataService(
                    new YubicoJsonMetadataService(),
                    new FidoMetadataServiceAdapter(
                            FidoMetadataService
                                    .builder()
                                    .useBlob(
                                            FidoMetadataDownloader
                                                    .builder()
                                                    .expectLegalHeader("Retrieval and use of this BLOB indicates acceptance of the appropriate agreement located at https://fidoalliance.org/metadata/metadata-legal-terms/")
                                                    .useDefaultTrustRoot()
                                                    .useTrustRootCacheFile(new File("webauthn-server-demo-fido-mds-trust-root-cache.bin"))
                                                    .useDefaultBlob()
                                                    .useBlobCacheFile(new File("webauthn-server-demo-fido-mds-blob-cache.bin"))
                                                    .build()
                                                    .loadCachedBlob()
                                    ).build()
                    )
            );
        } else {
            logger.info("Using only Yubico JSON file for attestation metadata.");
            return new YubicoJsonMetadataService();
        }
    }

    private final Clock clock = Clock.systemDefaultZone();
    private final ObjectMapper jsonMapper = JacksonCodecs.json();

    private final RelyingPartyV2<CredentialRegistration> rp;

    public WebAuthnServer() throws CertificateException, CertPathValidatorException, InvalidAlgorithmParameterException, Base64UrlException, DigestException, FidoMetadataDownloaderException, UnexpectedLegalHeader, IOException, NoSuchAlgorithmException, SignatureException, InvalidKeyException {
        this(new InMemoryRegistrationStorage(), newCache(), newCache(), Config.getRpIdentity(), Config.getOrigins());
    }

    public WebAuthnServer(
            InMemoryRegistrationStorage userStorage, Cache<ByteArray, RegistrationRequest> registerRequestStorage,
            Cache<ByteArray, AssertionRequestWrapper> assertRequestStorage, RelyingPartyIdentity rpIdentity,
            Set<String> origins
    ) throws CertificateException, CertPathValidatorException, InvalidAlgorithmParameterException,
            Base64UrlException, DigestException, FidoMetadataDownloaderException, UnexpectedLegalHeader,
            IOException, NoSuchAlgorithmException, SignatureException, InvalidKeyException {
        this.userStorage = userStorage;
        this.registerRequestStorage = registerRequestStorage;
        this.assertRequestStorage = assertRequestStorage;

        rp = RelyingParty
                .builder()
                .identity(rpIdentity)
                .credentialRepositoryV2(this.userStorage)
                .usernameRepository(this.userStorage)
                .origins(origins)
                .attestationConveyancePreference(Optional.of(AttestationConveyancePreference.DIRECT))
                .attestationTrustSource(metadataService)
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                .allowUntrustedAttestation(true)
                .validateSignatureCounter(true)
                .build();
    }

    private static ByteArray generateRandom(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return new ByteArray(bytes);
    }

    private static <K, V> Cache<K, V> newCache() {
        return CacheBuilder.newBuilder().maximumSize(100).expireAfterAccess(10, TimeUnit.MINUTES).build();
    }

    public Either<String, RegistrationRequest> startRegistration(
            @NonNull String username, @NonNull String displayName, Optional<String> credentialNickname,
            ResidentKeyRequirement residentKeyRequirement, Optional<ByteArray> sessionToken
    ) throws ExecutionException {
        logger.trace("startRegistration username: {}, credentialNickname: {}", username, credentialNickname);

        final Collection<CredentialRegistration> registrations = userStorage.getRegistrationsByUsername(username);
        final Optional<UserIdentity> existingUser = registrations.stream().findAny().map(CredentialRegistration::getUserIdentity);
        final boolean permissionGranted = existingUser.map(userIdentity -> sessions.isSessionForUser(userIdentity.getId(), sessionToken)).orElse(true);

        if (permissionGranted) {
            final UserIdentity registrationUserId = existingUser.orElseGet(() -> UserIdentity.builder().name(username).displayName(displayName).id(generateRandom(32)).build());

            RegistrationRequest request = new RegistrationRequest(
                    username, credentialNickname, generateRandom(32),
                    rp.startRegistration(
                            StartRegistrationOptions
                                    .builder()
                                    .user(registrationUserId)
                                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder().residentKey(residentKeyRequirement).build())
                                    .build()
                    ),
                    Optional.of(sessions.createSession(registrationUserId.getId()))
            );
            registerRequestStorage.put(request.getRequestId(), request);
            return Either.right(request);
        } else {
            return Either.left("The username \"" + username + "\" is already registered.");
        }
    }

    public Either<List<String>, SuccessfulRegistrationResult> finishRegistration(String responseJson) {
        logger.trace("finishRegistration responseJson: {}", responseJson);
        RegistrationResponse response = null;
        try {
            response = jsonMapper.readValue(responseJson, RegistrationResponse.class);
        } catch (IOException e) {
            logger.error("JSON error in finishRegistration; responseJson: {}", responseJson, e);
            return Either.left(Arrays.asList("Registration failed!", "Failed to decode response object.", e.getMessage()));
        }

        RegistrationRequest request = registerRequestStorage.getIfPresent(response.getRequestId());
        registerRequestStorage.invalidate(response.getRequestId());

        if (request == null) {
            logger.debug("fail finishRegistration responseJson: {}", responseJson);
            return Either.left(Arrays.asList("Registration failed!", "No such registration in progress."));
        } else {
            try {
                com.yubico.webauthn.RegistrationResult registration = rp.finishRegistration(
                        FinishRegistrationOptions
                                .builder()
                                .request(request.getPublicKeyCredentialCreationOptions())
                                .response(response.getCredential())
                                .build()
                );

                if (userStorage.userExists(request.getUsername())) {
                    boolean permissionGranted = false;

                    final boolean isValidSession = request.getSessionToken().map(
                            token -> sessions.isSessionForUser(request.getPublicKeyCredentialCreationOptions().getUser().getId(), token)
                    ).orElse(false);

                    logger.debug("Session token: {}", request.getSessionToken());
                    logger.debug("Valid session: {}", isValidSession);

                    if (isValidSession) {
                        permissionGranted = true;
                        logger.info("Session token accepted for user {}", request.getPublicKeyCredentialCreationOptions().getUser().getId());
                    }

                    logger.debug("permissionGranted: {}", permissionGranted);

                    if (!permissionGranted) {
                        throw new RegistrationFailedException(new IllegalArgumentException(String.format("User %s already exists", request.getUsername())));
                    }
                }

                return Either.right(
                        new SuccessfulRegistrationResult(
                                request, response,
                                addRegistration(request.getPublicKeyCredentialCreationOptions().getUser(), request.getCredentialNickname(), registration),
                                registration.isAttestationTrusted(),
                                sessions.createSession(request.getPublicKeyCredentialCreationOptions().getUser().getId())
                        )
                );
            } catch (RegistrationFailedException e) {
                logger.debug("fail finishRegistration responseJson: {}", responseJson, e);
                return Either.left(Arrays.asList("Registration failed!", e.getMessage()));
            } catch (Exception e) {
                logger.error("fail finishRegistration responseJson: {}", responseJson, e);
                return Either.left(Arrays.asList("Registration failed unexpectedly; this is likely a bug.", e.getMessage()));
            }
        }
    }

    public Either<List<String>, AssertionRequestWrapper> startAuthentication(Optional<String> username) {
        logger.trace("startAuthentication username: {}", username);

        if (username.isPresent() && !userStorage.userExists(username.get())) {
            return Either.left(Collections.singletonList("The username \"" + username.get() + "\" is not registered."));
        } else {
            AssertionRequestWrapper request = new AssertionRequestWrapper(
                    generateRandom(32),
                    rp.startAssertion(StartAssertionOptions.builder().username(username).build())
            );

            assertRequestStorage.put(request.getRequestId(), request);

            return Either.right(request);
        }
    }

    public Either<List<String>, SuccessfulAuthenticationResult> finishAuthentication(String responseJson) {
        logger.trace("finishAuthentication responseJson: {}", responseJson);

        final AssertionResponse response;
        try {
            response = jsonMapper.readValue(responseJson, AssertionResponse.class);
        } catch (IOException e) {
            logger.debug("Failed to decode response object", e);
            return Either.left(Arrays.asList("Assertion failed!", "Failed to decode response object.", e.getMessage()));
        }

        AssertionRequestWrapper request = assertRequestStorage.getIfPresent(response.getRequestId());
        assertRequestStorage.invalidate(response.getRequestId());

        if (request == null) {
            return Either.left(Arrays.asList("Assertion failed!", "No such assertion in progress."));
        } else {
            try {
                AssertionResultV2<CredentialRegistration> result = rp.finishAssertion(
                        FinishAssertionOptions.builder().request(request.getRequest()).response(response.getCredential()).build()
                );

                if (result.isSuccess()) {
                    try {
                        userStorage.updateSignatureCount(result);
                    } catch (Exception e) {
                        logger.error("Failed to update signature count for user \"{}\", credential \"{}\"", result.getCredential().getUsername(), response.getCredential().getId(), e);
                    }

                    return Either.right(
                            new SuccessfulAuthenticationResult(
                                    request,
                                    response,
                                    userStorage.getRegistrationsByUsername(result.getCredential().getUsername()),
                                    result.getCredential().getUsername(),
                                    sessions.createSession(result.getCredential().getUserHandle())
                            )
                    );
                } else {
                    return Either.left(Collections.singletonList("Assertion failed: Invalid assertion."));
                }
            } catch (AssertionFailedException e) {
                logger.debug("Assertion failed", e);
                return Either.left(Arrays.asList("Assertion failed!", e.getMessage()));
            } catch (Exception e) {
                logger.error("Assertion failed", e);
                return Either.left(Arrays.asList("Assertion failed unexpectedly; this is likely a bug.", e.getMessage()));
            }
        }
    }

    public Either<List<String>, DeregisterCredentialResult> deregisterCredential(@NonNull ByteArray sessionToken, ByteArray credentialId) {
        logger.trace("deregisterCredential session: {}, credentialId: {}", sessionToken, credentialId);

        if (credentialId == null || credentialId.getBytes().length == 0) {
            return Either.left(Collections.singletonList("Credential ID must not be empty."));
        }

        Optional<ByteArray> session = sessions.getSession(sessionToken);
        if (session.isPresent()) {
            ByteArray userHandle = session.get();
            Optional<String> username = userStorage.getUsernameForUserHandle(userHandle);

            if (username.isPresent()) {
                Optional<CredentialRegistration> credReg = userStorage.getRegistrationByUsernameAndCredentialId(username.get(), credentialId);
                if (credReg.isPresent()) {
                    userStorage.removeRegistrationByUsername(username.get(), credReg.get());

                    return Either.right(new DeregisterCredentialResult(credReg.get(), !userStorage.userExists(username.get())));
                } else {
                    return Either.left(Collections.singletonList("Credential ID not registered:" + credentialId));
                }
            } else {
                return Either.left(Collections.singletonList("Invalid user handle"));
            }
        } else {
            return Either.left(Collections.singletonList("Invalid session"));
        }
    }

    public <T> Either<List<String>, T> deleteAccount(String username, Supplier<T> onSuccess) {
        logger.trace("deleteAccount username: {}", username);

        if (username == null || username.isEmpty()) {
            return Either.left(Collections.singletonList("Username must not be empty."));
        }

        boolean removed = userStorage.removeAllRegistrations(username);

        if (removed) {
            return Either.right(onSuccess.get());
        } else {
            return Either.left(Collections.singletonList("Username not registered:" + username));
        }
    }

    private CredentialRegistration addRegistration(UserIdentity userIdentity, Optional<String> nickname, RegistrationResult result) {
        return addRegistration(
                userIdentity,
                nickname,
                RegisteredCredential
                        .builder()
                        .credentialId(result.getKeyId().getId())
                        .userHandle(userIdentity.getId())
                        .publicKeyCose(result.getPublicKeyCose())
                        .signatureCount(result.getSignatureCount()).build(),
                result.getKeyId().getTransports().orElseGet(TreeSet::new),
                metadataService.findEntries(result).stream().findAny()
        );
    }

    private CredentialRegistration addRegistration(
            UserIdentity userIdentity, Optional<String> nickname, RegisteredCredential credential,
            SortedSet<AuthenticatorTransport> transports, Optional<Object> attestationMetadata
    ) {
        CredentialRegistration reg = CredentialRegistration
                .builder()
                .userIdentity(userIdentity)
                .credentialNickname(nickname)
                .registrationTime(clock.instant())
                .credential(credential)
                .transports(transports)
                .attestationMetadata(attestationMetadata)
                .build();

        logger.debug("Adding registration: user: {}, nickname: {}, credential: {}", userIdentity, nickname, credential);
        userStorage.addRegistrationByUsername(userIdentity.getName(), reg);
        return reg;
    }

}
