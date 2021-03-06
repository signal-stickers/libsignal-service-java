/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api;


import com.google.protobuf.ByteString;

import org.signal.zkgroup.profiles.ProfileKey;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.ByteUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.FeatureFlags;
import org.whispersystems.signalservice.api.crypto.InvalidCiphertextException;
import org.whispersystems.signalservice.api.crypto.ProfileCipher;
import org.whispersystems.signalservice.api.crypto.ProfileCipherOutputStream;
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceInfo;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfileWrite;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity;
import org.whispersystems.signalservice.api.push.exceptions.NoContentException;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;
import org.whispersystems.signalservice.api.storage.SignalStorageCipher;
import org.whispersystems.signalservice.api.storage.SignalStorageManifest;
import org.whispersystems.signalservice.api.storage.SignalStorageModels;
import org.whispersystems.signalservice.api.storage.SignalStorageRecord;
import org.whispersystems.signalservice.api.storage.StorageKey;
import org.whispersystems.signalservice.api.storage.StorageManifestKey;
import org.whispersystems.signalservice.api.util.SleepTimer;
import org.whispersystems.signalservice.api.util.StreamDetails;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.contacts.crypto.ContactDiscoveryCipher;
import org.whispersystems.signalservice.internal.contacts.crypto.Quote;
import org.whispersystems.signalservice.internal.contacts.crypto.RemoteAttestation;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedQuoteException;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedResponseException;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryRequest;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryResponse;
import org.whispersystems.signalservice.internal.crypto.ProvisioningCipher;
import org.whispersystems.signalservice.internal.push.ProfileAvatarData;
import org.whispersystems.signalservice.internal.push.ProvisioningSocket;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.RemoteAttestationUtil;
import org.whispersystems.signalservice.internal.push.RemoteConfigResponse;
import org.whispersystems.signalservice.internal.push.http.ProfileCipherOutputStreamFactory;
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord;
import org.whispersystems.signalservice.internal.storage.protos.ReadOperation;
import org.whispersystems.signalservice.internal.storage.protos.StorageItem;
import org.whispersystems.signalservice.internal.storage.protos.StorageItems;
import org.whispersystems.signalservice.internal.storage.protos.StorageManifest;
import org.whispersystems.signalservice.internal.storage.protos.WriteOperation;
import org.whispersystems.signalservice.internal.util.DynamicCredentialsProvider;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionMessage;
import static org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisioningVersion;

/**
 * The main interface for creating, registering, and
 * managing a Signal Service account.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceAccountManager {

  private static final String TAG = SignalServiceAccountManager.class.getSimpleName();

  private final PushServiceSocket   pushServiceSocket;
  private final ProvisioningSocket  provisioningSocket;
  private final DynamicCredentialsProvider credentialsProvider;

    /**
   * Construct a SignalServiceAccountManager.
   *
   * @param configuration The URL for the Signal Service.
   * @param uuid The Signal Service UUID.
   * @param e164 The Signal Service phone number.
   * @param password A Signal Service password.
   * @param deviceId A integer which is provided by the server while linking.
   * @param signalAgent A string which identifies the client software.
   */
  public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                     UUID uuid, String e164, String password, int deviceId,
                                     String signalAgent, SleepTimer timer)
  {
    this(configuration, new DynamicCredentialsProvider(uuid, e164, password, null, deviceId), signalAgent, timer);
  }
  
  /**
   * Construct a SignalServiceAccountManager.
   *
   * @param configuration The URL for the Signal Service.
   * @param uuid The Signal Service UUID.
   * @param e164 The Signal Service phone number.
   * @param password A Signal Service password.
   * @param signalAgent A string which identifies the client software.
   */
  public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                     UUID uuid, String e164, String password,
                                     String signalAgent, SleepTimer timer)
  {
    this(configuration, uuid, e164, password, SignalServiceAddress.DEFAULT_DEVICE_ID, signalAgent, timer);
  }

  public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                     DynamicCredentialsProvider credentialsProvider,
                                     String signalAgent, SleepTimer timer)
  {
    this.credentialsProvider = credentialsProvider;
    this.provisioningSocket  = new ProvisioningSocket(configuration, signalAgent, timer);
    this.pushServiceSocket   = new PushServiceSocket(configuration, credentialsProvider, signalAgent);
  }

  public byte[] getSenderCertificate() throws IOException {
    return this.pushServiceSocket.getSenderCertificate();
  }

  public byte[] getSenderCertificateLegacy() throws IOException {
    return this.pushServiceSocket.getSenderCertificateLegacy();
  }

  /**
   * V1 Pin setting has been replaced by KeyBackupService.
   * Now you can only remove the old pin but there is no need to remove the old pin if setting a KBS Pin.
   */
  public void removeV1Pin() throws IOException {
    this.pushServiceSocket.removePin();
  }

  public UUID getOwnUuid() throws IOException {
    return this.pushServiceSocket.getOwnUuid();
  }

  public KeyBackupService getKeyBackupService(KeyStore iasKeyStore,
                                              String enclaveName,
                                              String mrenclave,
                                              int tries)
  {
    return new KeyBackupService(iasKeyStore, enclaveName, mrenclave, pushServiceSocket, tries);
  }

  /**
   * Register/Unregister a Google Cloud Messaging registration ID.
   *
   * @param gcmRegistrationId The GCM id to register.  A call with an absent value will unregister.
   * @throws IOException
   */
  public void setGcmId(Optional<String> gcmRegistrationId) throws IOException {
    if (gcmRegistrationId.isPresent()) {
      this.pushServiceSocket.registerGcmId(gcmRegistrationId.get());
    } else {
      this.pushServiceSocket.unregisterGcmId();
    }
  }

  /**
   * Request a push challenge. A number will be pushed to the GCM (FCM) id. This can then be used
   * during SMS/call requests to bypass the CAPTCHA.
   *
   * @param gcmRegistrationId The GCM (FCM) id to use.
   * @param e164number        The number to associate it with.
   * @throws IOException
   */
  public void requestPushChallenge(String gcmRegistrationId, String e164number) throws IOException {
    this.pushServiceSocket.requestPushChallenge(gcmRegistrationId, e164number);
  }

  /**
   * Request an SMS verification code.  On success, the server will send
   * an SMS verification code to this Signal user.
   *
   * @param androidSmsRetrieverSupported
   * @param captchaToken                 If the user has done a CAPTCHA, include this.
   * @param challenge                    If present, it can bypass the CAPTCHA.
   * @throws IOException
   */
  public void requestSmsVerificationCode(boolean androidSmsRetrieverSupported, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    this.pushServiceSocket.requestSmsVerificationCode(androidSmsRetrieverSupported, captchaToken, challenge);
  }

  /**
   * Request a Voice verification code.  On success, the server will
   * make a voice call to this Signal user.
   *
   * @param locale
   * @param captchaToken If the user has done a CAPTCHA, include this.
   * @param challenge    If present, it can bypass the CAPTCHA.
   * @throws IOException
   */
  public void requestVoiceVerificationCode(Locale locale, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    this.pushServiceSocket.requestVoiceVerificationCode(locale, captchaToken, challenge);
  }

  /**
   * Verify a Signal Service account with a received SMS or voice verification code.
   *
   * @param verificationCode The verification code received via SMS or Voice
   *                         (see {@link #requestSmsVerificationCode} and
   *                         {@link #requestVoiceVerificationCode}).
   * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key,
   *                     concatenated.
   * @param signalProtocolRegistrationId A random 14-bit number that identifies this Signal install.
   *                                     This value should remain consistent across registrations for the
   *                                     same install, but probabilistically differ across registrations
   *                                     for separate installs.
   * @param pin Deprecated, only supply the pin if you did not find a registrationLock on KBS.
   * @param registrationLock Only supply if found on KBS.
   * @return The UUID of the user that was registered.
   * @throws IOException
   */
  public UUID verifyAccountWithCode(String verificationCode, String signalingKey, int signalProtocolRegistrationId, boolean fetchesMessages,
                                    String pin, String registrationLock,
                                    byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                                    SignalServiceProfile.Capabilities capabilities)
      throws IOException
  {
    return this.pushServiceSocket.verifyAccountCode(verificationCode, signalingKey,
                                                    signalProtocolRegistrationId,
                                                    fetchesMessages,
                                                    pin, registrationLock,
                                                    unidentifiedAccessKey,
                                                    unrestrictedUnidentifiedAccess,
                                                    capabilities);
  }

  /**
   * Refresh account attributes with server.
   *
   * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key, concatenated.
   * @param signalProtocolRegistrationId A random 14-bit number that identifies this Signal install.
   *                                     This value should remain consistent across registrations for the same
   *                                     install, but probabilistically differ across registrations for
   *                                     separate installs.
   * @param pin Only supply if pin has not yet been migrated to KBS.
   * @param registrationLock Only supply if found on KBS.
   *
   * @throws IOException
   */
  public void setAccountAttributes(String signalingKey, int signalProtocolRegistrationId, boolean fetchesMessages,
                                   String pin, String registrationLock,
                                   byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                                   SignalServiceProfile.Capabilities capabilities)
      throws IOException
  {
    this.pushServiceSocket.setAccountAttributes(signalingKey, signalProtocolRegistrationId, fetchesMessages,
                                                pin, registrationLock,
                                                unidentifiedAccessKey, unrestrictedUnidentifiedAccess,
                                                capabilities);
  }

  /**
   * Register an identity key, signed prekey, and list of one time prekeys
   * with the server.
   *
   * @param identityKey The client's long-term identity keypair.
   * @param signedPreKey The client's signed prekey.
   * @param oneTimePreKeys The client's list of one-time prekeys.
   *
   * @throws IOException
   */
  public void setPreKeys(IdentityKey identityKey, SignedPreKeyRecord signedPreKey, List<PreKeyRecord> oneTimePreKeys)
      throws IOException
  {
    this.pushServiceSocket.registerPreKeys(identityKey, signedPreKey, oneTimePreKeys);
  }

  /**
   * @return The server's count of currently available (eg. unused) prekeys for this user.
   * @throws IOException
   */
  public int getPreKeysCount() throws IOException {
    return this.pushServiceSocket.getAvailablePreKeys();
  }

  /**
   * Set the client's signed prekey.
   *
   * @param signedPreKey The client's new signed prekey.
   * @throws IOException
   */
  public void setSignedPreKey(SignedPreKeyRecord signedPreKey) throws IOException {
    this.pushServiceSocket.setCurrentSignedPreKey(signedPreKey);
  }

  /**
   * @return The server's view of the client's current signed prekey.
   * @throws IOException
   */
  public SignedPreKeyEntity getSignedPreKey() throws IOException {
    return this.pushServiceSocket.getCurrentSignedPreKey();
  }

  /**
   * Checks whether a contact is currently registered with the server.
   *
   * @param e164number The contact to check.
   * @return An optional ContactTokenDetails, present if registered, absent if not.
   * @throws IOException
   */
  public Optional<ContactTokenDetails> getContact(String e164number) throws IOException {
    String              contactToken        = createDirectoryServerToken(e164number, true);
    ContactTokenDetails contactTokenDetails = this.pushServiceSocket.getContactTokenDetails(contactToken);

    if (contactTokenDetails != null) {
      contactTokenDetails.setNumber(e164number);
    }

    return Optional.fromNullable(contactTokenDetails);
  }

  /**
   * Checks which contacts in a set are registered with the server.
   *
   * @param e164numbers The contacts to check.
   * @return A list of ContactTokenDetails for the registered users.
   * @throws IOException
   */
  public List<ContactTokenDetails> getContacts(Set<String> e164numbers)
      throws IOException
  {
    Map<String, String>       contactTokensMap = createDirectoryServerTokenMap(e164numbers);
    List<ContactTokenDetails> activeTokens     = this.pushServiceSocket.retrieveDirectory(contactTokensMap.keySet());

    for (ContactTokenDetails activeToken : activeTokens) {
      activeToken.setNumber(contactTokensMap.get(activeToken.getToken()));
    }

    return activeTokens;
  }

  public List<String> getRegisteredUsers(KeyStore iasKeyStore, Set<String> e164numbers, String enclaveId)
      throws IOException, Quote.InvalidQuoteFormatException, UnauthenticatedQuoteException, SignatureException, UnauthenticatedResponseException
  {
    try {
      String                 authorization     = pushServiceSocket.getContactDiscoveryAuthorization();
      RemoteAttestation      remoteAttestation = RemoteAttestationUtil.getAndVerifyRemoteAttestation(pushServiceSocket, PushServiceSocket.ClientSet.ContactDiscovery, iasKeyStore, enclaveId, enclaveId, authorization);
      List<String>           addressBook       = new LinkedList<>();

      for (String e164number : e164numbers) {
        addressBook.add(e164number.substring(1));
      }

      DiscoveryRequest  request  = ContactDiscoveryCipher.createDiscoveryRequest(addressBook, remoteAttestation);
      DiscoveryResponse response = pushServiceSocket.getContactDiscoveryRegisteredUsers(authorization, request, remoteAttestation.getCookies(), enclaveId);
      byte[]            data     = ContactDiscoveryCipher.getDiscoveryResponseData(response, remoteAttestation);

      Iterator<String> addressBookIterator = addressBook.iterator();
      List<String>     results             = new LinkedList<>();

      for (byte aData : data) {
        String candidate = addressBookIterator.next();

        if (aData != 0) results.add('+' + candidate);
      }

      return results;
    } catch (InvalidCiphertextException e) {
      throw new UnauthenticatedResponseException(e);
    }
  }

  public void reportContactDiscoveryServiceMatch() {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceMatch();
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery result match failed. Ignoring.", e);
    }
  }

  public void reportContactDiscoveryServiceMismatch() {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceMismatch();
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery result mismatch failed. Ignoring.", e);
    }
  }

  public void reportContactDiscoveryServiceAttestationError(String reason) {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceAttestationError(reason);
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery attestation error failed. Ignoring.", e);
    }
  }

  public void reportContactDiscoveryServiceUnexpectedError(String reason) {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceUnexpectedError(reason);
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery unexpected error failed. Ignoring.", e);
    }
  }

  /**
   * Request a UUID from the server for linking as a new device.
   * Called by the new device.
   * @return The UUID, Base64 encoded
   * @throws TimeoutException
   * @throws IOException
   */
  public String getNewDeviceUuid() throws TimeoutException, IOException {
    return provisioningSocket.getProvisioningUuid().getUuid();
  }
  public long getStorageManifestVersion() throws IOException {
    try {
      String          authToken       = this.pushServiceSocket.getStorageAuth();
      StorageManifest storageManifest = this.pushServiceSocket.getStorageManifest(authToken);

      return  storageManifest.getVersion();
    } catch (NotFoundException e) {
      return 0;
    }
  }

  public Optional<SignalStorageManifest> getStorageManifestIfDifferentVersion(StorageKey storageKey, long manifestVersion) throws IOException, InvalidKeyException {
    try {
      String          authToken       = this.pushServiceSocket.getStorageAuth();
      StorageManifest storageManifest = this.pushServiceSocket.getStorageManifestIfDifferentVersion(authToken, manifestVersion);

      if (storageManifest.getValue().isEmpty()) {
        Log.w(TAG, "Got an empty storage manifest!");
        return Optional.absent();
      }

      byte[]         rawRecord      = SignalStorageCipher.decrypt(storageKey.deriveManifestKey(storageManifest.getVersion()), storageManifest.getValue().toByteArray());
      ManifestRecord manifestRecord = ManifestRecord.parseFrom(rawRecord);
      List<byte[]>   keys           = new ArrayList<>(manifestRecord.getKeysCount());

      for (ByteString key : manifestRecord.getKeysList()) {
        keys.add(key.toByteArray());
      }

      return Optional.of(new SignalStorageManifest(manifestRecord.getVersion(), keys));
    } catch (NoContentException e) {
      return Optional.absent();
    }
  }

  public List<SignalStorageRecord> readStorageRecords(StorageKey storageKey, List<byte[]> storageKeys) throws IOException, InvalidKeyException {
    ReadOperation.Builder operation = ReadOperation.newBuilder();

    for (byte[] key : storageKeys) {
      operation.addReadKey(ByteString.copyFrom(key));
    }

    String                    authToken = this.pushServiceSocket.getStorageAuth();
    StorageItems              items     = this.pushServiceSocket.readStorageItems(authToken, operation.build());
    List<SignalStorageRecord> result    = new ArrayList<>(items.getItemsCount());

    if (items.getItemsCount() != storageKeys.size()) {
      Log.w(TAG, "Failed to find all remote keys! Requested: " + storageKeys.size() + ", Found: " + items.getItemsCount());
    }

    for (StorageItem item : items.getItemsList()) {
      if (item.hasKey()) {
        result.add(SignalStorageModels.remoteToLocalStorageRecord(item, storageKey));
      } else {
        Log.w(TAG, "Encountered a StorageItem with no key! Skipping.");
      }
    }

    return result;
  }
  /**
   * @return If there was a conflict, the latest {@link SignalStorageManifest}. Otherwise absent.
   */
  public Optional<SignalStorageManifest> resetStorageRecords(StorageKey storageKey,
                                                             SignalStorageManifest manifest,
                                                             List<SignalStorageRecord> allRecords)
      throws IOException, InvalidKeyException
  {
    return writeStorageRecords(storageKey, manifest, allRecords, Collections.<byte[]>emptyList(), true);
  }

  /**
   * @return If there was a conflict, the latest {@link SignalStorageManifest}. Otherwise absent.
   */
  public Optional<SignalStorageManifest> writeStorageRecords(StorageKey storageKey,
                                                             SignalStorageManifest manifest,
                                                             List<SignalStorageRecord> inserts,
                                                             List<byte[]> deletes)
      throws IOException, InvalidKeyException
  {
    return writeStorageRecords(storageKey, manifest, inserts, deletes, false);
  }

  /**
   * @return If there was a conflict, the latest {@link SignalStorageManifest}. Otherwise absent.
   */
  private Optional<SignalStorageManifest> writeStorageRecords(StorageKey storageKey,
                                                              SignalStorageManifest manifest,
                                                              List<SignalStorageRecord> inserts,
                                                              List<byte[]> deletes,
                                                              boolean clearAll)
      throws IOException, InvalidKeyException
  {
    ManifestRecord.Builder manifestRecordBuilder = ManifestRecord.newBuilder().setVersion(manifest.getVersion());

    for (byte[] key : manifest.getStorageKeys()) {
      manifestRecordBuilder.addKeys(ByteString.copyFrom(key));
    }

    String             authToken       = this.pushServiceSocket.getStorageAuth();
    StorageManifestKey manifestKey     = storageKey.deriveManifestKey(manifest.getVersion());
    byte[]             encryptedRecord = SignalStorageCipher.encrypt(manifestKey, manifestRecordBuilder.build().toByteArray());
    StorageManifest    storageManifest = StorageManifest.newBuilder()
                                                         .setVersion(manifest.getVersion())
                                                         .setValue(ByteString.copyFrom(encryptedRecord))
                                                         .build();
    WriteOperation.Builder writeBuilder = WriteOperation.newBuilder().setManifest(storageManifest);

    for (SignalStorageRecord insert : inserts) {
      writeBuilder.addInsertItem(SignalStorageModels.localToRemoteStorageRecord(insert, storageKey));
    }

    if (clearAll) {
      writeBuilder.setClearAll(true);
    } else {
      for (byte[] delete : deletes) {
        writeBuilder.addDeleteKey(ByteString.copyFrom(delete));
      }
    }

    Optional<StorageManifest> conflict = this.pushServiceSocket.writeStorageContacts(authToken, writeBuilder.build());

    if (conflict.isPresent()) {
      StorageManifestKey conflictKey       = storageKey.deriveManifestKey(conflict.get().getVersion());
      byte[]             rawManifestRecord = SignalStorageCipher.decrypt(conflictKey, conflict.get().getValue().toByteArray());
      ManifestRecord     record            = ManifestRecord.parseFrom(rawManifestRecord);
      List<byte[]>       keys              = new ArrayList<>(record.getKeysCount());

      for (ByteString key : record.getKeysList()) {
        keys.add(key.toByteArray());
      }

      SignalStorageManifest conflictManifest = new SignalStorageManifest(record.getVersion(), keys);

      return Optional.of(conflictManifest);
    } else {
      return Optional.absent();
    }
  }

  public Map<String, Boolean> getRemoteConfig() throws IOException {
    RemoteConfigResponse response = this.pushServiceSocket.getRemoteConfig();
    Map<String, Boolean> out      = new HashMap<>();

    for (RemoteConfigResponse.Config config : response.getConfig()) {
      out.put(config.getName(), config.isEnabled());
    }

    return out;
  }

  /**
   * Request a Code for verification of a new device.
   * Called by an already verified device.
   * @return An verification code. String of 6 digits
   * @throws IOException
   */
  public String getNewDeviceVerificationCode() throws IOException {
    return this.pushServiceSocket.getNewDeviceVerificationCode();
  }
  
  /**
   * Finishes a registration as a new device. Called by the new device.<br>
   * This method blocks until the already verified device has verified this device.
   * @param tempIdentity A temporary identity. Must be the same as the one given to the already verified device.
   * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key, concatenated.
   * @param supportsSms A boolean which indicates whether this device can receive SMS to the account's number.
   * @param fetchesMessages A boolean which indicates whether this device fetches messages.
   * @param registrationId A random integer generated at install time.
   * @param deviceName A name for this device, not its user agent.
   * @return Contains the account's permanent IdentityKeyPair and it's number along the deviceId given by the server.
   * @throws TimeoutException
   * @throws IOException
   * @throws InvalidKeyException
   */
  public NewDeviceRegistrationReturn finishNewDeviceRegistration(IdentityKeyPair tempIdentity, String signalingKey, boolean supportsSms, boolean fetchesMessages, int registrationId, String deviceName) throws TimeoutException, IOException, InvalidKeyException {
    ProvisionMessage msg = provisioningSocket.getProvisioningMessage(tempIdentity);
    credentialsProvider.setE164(msg.getNumber());
    UUID uuid = UuidUtil.parseOrNull(msg.getUuid());
    // Not setting Uuid here, as that causes a 400 Bad Request
    // when calling the finishNewDeviceRegistration endpoint
    // credentialsProvider.setUuid(uuid);
    String provisioningCode = msg.getProvisioningCode();
    byte[] publicKeyBytes = msg.getIdentityKeyPublic().toByteArray();
    if (publicKeyBytes.length == 32) {
      // The public key is missing the type specifier, probably from iOS
      // Signal-Desktop handles this by ignoring the sent public key and regenerating it from the private key
      byte[] type = {Curve.DJB_TYPE};
      publicKeyBytes = ByteUtil.combine(type, publicKeyBytes);
    }
    ECPublicKey publicKey = Curve.decodePoint(publicKeyBytes, 0);
    final byte[] privateKeyBytes = msg.getIdentityKeyPrivate().toByteArray();
    ECPrivateKey privateKey = Curve.decodePrivatePoint(privateKeyBytes);
    IdentityKeyPair identity = new IdentityKeyPair(new IdentityKey(publicKey), privateKey);
    int deviceId = this.pushServiceSocket.finishNewDeviceRegistration(provisioningCode, signalingKey, supportsSms, fetchesMessages, registrationId, deviceName);
    credentialsProvider.setDeviceId(deviceId);
    return new NewDeviceRegistrationReturn(identity, deviceId, msg.getNumber(), uuid, msg.hasProfileKey() ? msg.getProfileKey().toByteArray() : null, msg.hasReadReceipts() && msg.getReadReceipts());
  }

  public void addDevice(String deviceIdentifier,
                        ECPublicKey deviceKey,
                        IdentityKeyPair identityKeyPair,
                        Optional<byte[]> profileKey,
                        String code)
      throws InvalidKeyException, IOException
  {
    ProvisioningCipher cipher  = new ProvisioningCipher(deviceKey);
    ProvisionMessage.Builder message = ProvisionMessage.newBuilder()
                                                       .setIdentityKeyPublic(ByteString.copyFrom(identityKeyPair.getPublicKey().serialize()))
                                                       .setIdentityKeyPrivate(ByteString.copyFrom(identityKeyPair.getPrivateKey().serialize()))
                                                       .setProvisioningCode(code)
                                                       .setProvisioningVersion(ProvisioningVersion.CURRENT_VALUE);

    String e164 = credentialsProvider.getE164();
    UUID   uuid = credentialsProvider.getUuid();

    if (e164 != null) {
      message.setNumber(e164);
    } else {
      throw new AssertionError("Missing phone number!");
    }

    if (uuid != null) {
      message.setUuid(uuid.toString());
    } else {
      Log.w(TAG, "[addDevice] Missing UUID.");
    }

    if (profileKey.isPresent()) {
      message.setProfileKey(ByteString.copyFrom(profileKey.get()));
    }

    byte[] ciphertext = cipher.encrypt(message.build());
    this.pushServiceSocket.sendProvisioningMessage(deviceIdentifier, ciphertext);
  }

  public List<DeviceInfo> getDevices() throws IOException {
    return this.pushServiceSocket.getDevices();
  }

  public void removeDevice(long deviceId) throws IOException {
    this.pushServiceSocket.removeDevice(deviceId);
  }

  public TurnServerInfo getTurnServerInfo() throws IOException {
    return this.pushServiceSocket.getTurnServerInfo();
  }

  public void setProfileName(ProfileKey key, String name)
      throws IOException
  {
    if (FeatureFlags.VERSIONED_PROFILES) {
      throw new AssertionError();
    }

    if (name == null) name = "";

    String ciphertextName = Base64.encodeBytesWithoutPadding(new ProfileCipher(key).encryptName(name.getBytes(StandardCharsets.UTF_8), ProfileCipher.NAME_PADDED_LENGTH));

    this.pushServiceSocket.setProfileName(ciphertextName);
  }

  public void setProfileAvatar(ProfileKey key, StreamDetails avatar)
      throws IOException
  {
    if (FeatureFlags.VERSIONED_PROFILES) {
      throw new AssertionError();
    }

    ProfileAvatarData profileAvatarData = null;

    if (avatar != null) {
      profileAvatarData = new ProfileAvatarData(avatar.getStream(),
                                                ProfileCipherOutputStream.getCiphertextLength(avatar.getLength()),
                                                avatar.getContentType(),
                                                new ProfileCipherOutputStreamFactory(key));
    }

    this.pushServiceSocket.setProfileAvatar(profileAvatarData);
  }

  public void setVersionedProfile(ProfileKey profileKey, String name, StreamDetails avatar)
    throws IOException
  {
    if (!FeatureFlags.VERSIONED_PROFILES) {
      throw new AssertionError();
    }

    if (name == null) name = "";

    byte[]            ciphertextName    = new ProfileCipher(profileKey).encryptName(name.getBytes(StandardCharsets.UTF_8), ProfileCipher.NAME_PADDED_LENGTH);
    boolean           hasAvatar         = avatar != null;
    ProfileAvatarData profileAvatarData = null;

    if (hasAvatar) {
      profileAvatarData = new ProfileAvatarData(avatar.getStream(),
                                                ProfileCipherOutputStream.getCiphertextLength(avatar.getLength()),
                                                avatar.getContentType(),
                                                new ProfileCipherOutputStreamFactory(profileKey));
    }

    this.pushServiceSocket.writeProfile(new SignalServiceProfileWrite(profileKey.getProfileKeyVersion().serialize(),
                                                                      ciphertextName,
                                                                      hasAvatar,
                                                                      profileKey.getCommitment().serialize()),
                                                                      profileAvatarData);
  }

  public void setUsername(String username) throws IOException {
    this.pushServiceSocket.setUsername(username);
  }

  public void deleteUsername() throws IOException {
    this.pushServiceSocket.deleteUsername();
  }

  public void setSoTimeoutMillis(long soTimeoutMillis) {
    this.pushServiceSocket.setSoTimeoutMillis(soTimeoutMillis);
  }

  public void cancelInFlightRequests() {
    this.pushServiceSocket.cancelInFlightRequests();
  }

  private String createDirectoryServerToken(String e164number, boolean urlSafe) {
    try {
      MessageDigest digest  = MessageDigest.getInstance("SHA1");
      byte[]        token   = Util.trim(digest.digest(e164number.getBytes()), 10);
      String        encoded = Base64.encodeBytesWithoutPadding(token);

      if (urlSafe) return encoded.replace('+', '-').replace('/', '_');
      else         return encoded;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private Map<String, String> createDirectoryServerTokenMap(Collection<String> e164numbers) {
    Map<String,String> tokenMap = new HashMap<>(e164numbers.size());

    for (String number : e164numbers) {
      tokenMap.put(createDirectoryServerToken(number, false), number);
    }

    return tokenMap;
  }
  
  /**
   * Helper class for holding the returns of finishNewDeviceRegistration()
   */
  public static class NewDeviceRegistrationReturn {
    private final IdentityKeyPair identity;
    private final int             deviceId;
    private final String          number;
    private final UUID            uuid;
    private final byte[]          profileKey;
    private final boolean         readReceipts;

    NewDeviceRegistrationReturn(IdentityKeyPair identity, int deviceId, String number, UUID uuid, byte[] profileKey, boolean readReceipts) {
      this.identity     = identity;
      this.deviceId     = deviceId;
      this.number       = number;
      this.uuid         = uuid;
      this.profileKey   = profileKey;
      this.readReceipts = readReceipts;
    }

    /**
     * @return The account's permanent IdentityKeyPair
     */
    public IdentityKeyPair getIdentity() {
      return identity;
    }

    /**
     * @return The deviceId for this device given by the server
     */
    public int getDeviceId() {
      return deviceId;
    }

    /**
     * @return The account's number
     */
    public String getNumber() {
      return number;
    }

    /**
     * @return The account's uuid
     */
    public UUID getUuid() {
      return uuid;
    }

    /**
     * @return The account's profile key or null
     */
    public byte[] getProfileKey() {
      return profileKey;
    }

    /**
     * @return The account's read receipts setting
     */
    public boolean isReadReceipts() {
      return readReceipts;
    }
  }
}
