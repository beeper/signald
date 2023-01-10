package io.finn.signald.jobs;

import io.finn.signald.Account;
import io.finn.signald.MessageReceiver;
import io.finn.signald.db.Database;
import io.finn.signald.db.Recipient;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import io.sentry.Sentry;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asamk.signal.TrustLevel;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.libsignal.zkgroup.groups.GroupSecretParams;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.storage.*;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord;

public class SyncStorageDataJob implements Job {
  private static final Logger logger = LogManager.getLogger();
  private final Account account;

  public SyncStorageDataJob(Account account) { this.account = account; }

  @Override
  public void run() throws NoSuchAccountException, SQLException, ServerNotFoundException, IOException, InvalidProxyException, InvalidKeyException, InvalidInputException,
                           InvalidGroupStateException, VerificationFailedException {
    logger.debug("syncing data from storage service");

    StorageKey storageKey = account.getStorageKey();
    if (storageKey == null) {
      logger.debug("skipping storage sync because storage keys not available");
      if (account.getDeviceId() != SignalServiceAddress.DEFAULT_DEVICE_ID) {
        logger.debug("queuing job to request storage keys from primary device");
        BackgroundJobRunnerThread.queue(new SendSyncRequestJob(account, SignalServiceProtos.SyncMessage.Request.Type.KEYS));
      }
      return;
    }

    SignalServiceAccountManager accountManager = account.getSignalDependencies().getAccountManager();
    Optional<SignalStorageManifest> manifestOptional;
    try {
      manifestOptional = accountManager.getStorageManifestIfDifferentVersion(storageKey, account.getStorageManifestVersion());
    } catch (InvalidKeyException e) {
      logger.warn("storage manifest could not be decrypted, ignoring");
      return;
    }

    if (manifestOptional.isEmpty()) {
      logger.debug("manifest is up to date, does not exist or couldn't be decrypted, ignoring.");
      return;
    }
    logger.debug("got data from storage service");

    SignalStorageManifest manifest = manifestOptional.get();
    account.setStorageManifestVersion(manifest.getVersion());

    Optional<StorageId> accountId = manifest.getAccountStorageId();
    if (accountId.isEmpty()) {
      logger.warn("Manifest has no account record, ignoring.");
      return;
    }

    logger.debug("reading account record");
    readAccountRecord(manifest);

    List<SignalStorageRecord> records = getSignalStorageRecords(manifest.getStorageIds());
    logger.debug("reading all {} records", records.size());
    for (SignalStorageRecord record : records) {
      if (record.isUnknown() || record.getType() == ManifestRecord.Identifier.Type.ACCOUNT_VALUE) {
        continue;
      }

      if (record.getType() == ManifestRecord.Identifier.Type.GROUPV2_VALUE) {
        logger.debug("reading groupv2 record");
        readGroupV2Record(record);
      } else if (record.getType() == ManifestRecord.Identifier.Type.CONTACT_VALUE) {
        logger.debug("reading contact record");
        readContactRecord(record);
      } else {
        logger.debug("ignoring record of unknown type {}", ManifestRecord.Identifier.Type.forNumber(record.getType()).name());
      }
    }
    logger.debug("Done reading data from remote storage");
    MessageReceiver.broadcastStorageStateChange(account.getUUID(), manifest.getVersion());
  }

  private void readAccountRecord(final SignalStorageManifest manifest) throws IOException, NoSuchAccountException, SQLException, ServerNotFoundException, InvalidProxyException {
    Optional<StorageId> accountId = manifest.getAccountStorageId();
    if (accountId.isEmpty()) {
      logger.warn("Manifest has no account record, ignoring.");
      return;
    }

    SignalStorageRecord record = getSignalStorageRecord(accountId.get());
    if (record == null) {
      logger.warn("Could not find account record, even though we had an ID, ignoring.");
      return;
    }

    if (record.getAccount().isEmpty()) {
      logger.warn("The storage record didn't actually have an account, ignoring.");
      return;
    }
    SignalAccountRecord accountRecord = record.getAccount().get();

    if (!accountRecord.getE164().equals(account.getE164())) {
      // TODO implement changed number handling
    }

    // TODO: store configuration
    // accountRecord.isReadReceiptsEnabled()
    // accountRecord.isTypingIndicatorsEnabled()
    // accountRecord.isSealedSenderIndicatorsEnabled()
    // accountRecord.isLinkPreviewsEnabled()

    //    if (accountRecord.getPhoneNumberSharingMode() != AccountRecord.PhoneNumberSharingMode.UNRECOGNIZED) {
    //            account.getConfigurationStore()
    //                    .setPhoneNumberSharingMode(switch (accountRecord.getPhoneNumberSharingMode()) {
    //                        case EVERYBODY -> AccountRecord.PhoneNumberSharingMode.EVERYBODY;
    //                        case NOBODY -> AccountRecord.PhoneNumberSharingMode.NOBODY;
    //                        default -> AccountRecord.PhoneNumberSharingMode.CONTACTS_ONLY;
    //                    });
    //        }
    //        account.getConfigurationStore().setPhoneNumberUnlisted(accountRecord.isPhoneNumberUnlisted());

    if (accountRecord.getProfileKey().isPresent()) {
      ProfileKey profileKey;
      try {
        profileKey = new ProfileKey(accountRecord.getProfileKey().get());
      } catch (InvalidInputException e) {
        logger.warn("Received invalid profile key from storage");
        profileKey = null;
      }
      if (profileKey != null) {
        account.getDB().ProfileKeysTable.setProfileKey(account.getSelf(), profileKey);
      }
    }
  }

  // "contact" records appear to contain PROFILE information and contact information
  private void readContactRecord(final SignalStorageRecord record) throws SQLException, IOException {
    if (record == null || record.getContact().isEmpty()) {
      return;
    }

    SignalContactRecord contactRecord = record.getContact().get();
    ServiceId serviceId = contactRecord.getServiceId();

    Recipient recipient;
    try {
      recipient = account.getDB().RecipientsTable.get(serviceId);
    } catch (SQLException e) {
      logger.error("error getting recipient for storage sync", e);
      Sentry.captureException(e);
      return;
    }

    if (contactRecord.getProfileGivenName().isPresent() || contactRecord.getProfileFamilyName().isPresent()) {
      logger.debug("storing profile in local database");
      Database db = account.getDB();
      if (contactRecord.getProfileGivenName().isPresent() || contactRecord.getProfileFamilyName().isPresent()) {
        String name;
        if (contactRecord.getProfileGivenName().isPresent() && contactRecord.getProfileFamilyName().isPresent()) {
          name = contactRecord.getProfileGivenName().get() + "\0" + contactRecord.getProfileFamilyName().get();
        } else {
          name = contactRecord.getProfileGivenName().orElse("") + contactRecord.getProfileFamilyName().orElse("");
        }
        db.ProfilesTable.setSerializedName(recipient, name);
      }
    }

    if (contactRecord.getProfileKey().isPresent()) {
      logger.debug("storing profile key in local database");
      try {
        ProfileKey profileKey = new ProfileKey(contactRecord.getProfileKey().get());
        account.getDB().ProfileKeysTable.setProfileKey(recipient, profileKey);
      } catch (InvalidInputException e) {
        logger.warn("Received invalid contact profile key from storage");
      }
    }

    if (contactRecord.getIdentityKey().isPresent()) {
      logger.debug("storing identity key in local database");
      try {
        IdentityKey identityKey = new IdentityKey(contactRecord.getIdentityKey().get());
        TrustLevel trustLevel = TrustLevel.fromIdentityState(contactRecord.getIdentityState());
        account.getProtocolStore().saveIdentity(recipient, identityKey, trustLevel);
      } catch (InvalidKeyException e) {
        logger.warn("Received invalid contact identity key from storage");
      }
    }
  }

  private void readGroupV2Record(final SignalStorageRecord record) throws NoSuchAccountException, SQLException, ServerNotFoundException, IOException, InvalidProxyException,
                                                                          InvalidInputException, InvalidGroupStateException, VerificationFailedException {
    if (record == null || record.getGroupV2().isEmpty()) {
      return;
    }

    SignalGroupV2Record groupV2Record = record.getGroupV2().get();
    if (groupV2Record.isArchived()) {
      logger.debug("skipping archived group");
      return;
    }

    final GroupMasterKey groupMasterKey;
    try {
      groupMasterKey = new GroupMasterKey(groupV2Record.getMasterKeyBytes());
    } catch (InvalidInputException e) {
      logger.warn("Received invalid group master key from storage");
      return;
    }

    logger.debug("refreshing group received from storage");
    account.getGroups().getGroup(GroupSecretParams.deriveFromMasterKey(groupMasterKey), -1);
  }

  private SignalStorageRecord getSignalStorageRecord(final StorageId accountId)
      throws IOException, NoSuchAccountException, SQLException, ServerNotFoundException, InvalidProxyException {
    List<SignalStorageRecord> records = getSignalStorageRecords(Collections.singletonList(accountId));
    return records.size() > 0 ? records.get(0) : null;
  }

  private List<SignalStorageRecord> getSignalStorageRecords(final List<StorageId> storageIds)
      throws IOException, NoSuchAccountException, SQLException, ServerNotFoundException, InvalidProxyException {
    SignalServiceAccountManager accountManager = account.getSignalDependencies().getAccountManager();
    logger.debug("reading {} storage record(s)", storageIds.size());
    try {
      return accountManager.readStorageRecords(account.getStorageKey(), storageIds);
    } catch (InvalidKeyException e) {
      logger.warn("Failed to read storage records, ignoring.");
      return List.of();
    }
  }
}
