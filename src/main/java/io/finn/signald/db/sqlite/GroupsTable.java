/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.db.sqlite;

import com.google.protobuf.InvalidProtocolBufferException;
import io.finn.signald.Account;
import io.finn.signald.ServiceConfig;
import io.finn.signald.Util;
import io.finn.signald.clientprotocol.v1.JsonGroupV2Info;
import io.finn.signald.db.Database;
import io.finn.signald.db.IGroupsTable;
import io.finn.signald.db.Recipient;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import io.finn.signald.util.FileUtil;
import io.finn.signald.util.GroupsUtil;
import io.finn.signald.util.SenderKeyUtil;
import io.sentry.Sentry;
import java.io.*;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupIdentifier;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.libsignal.zkgroup.groups.GroupSecretParams;
import org.signal.storageservice.protos.groups.Member;
import org.signal.storageservice.protos.groups.local.*;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.groupsv2.GroupChangeReconstruct;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.DistributionId;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.util.Base64;

public class GroupsTable implements IGroupsTable {
  private static String groupAvatarPath;

  private static final Logger logger = LogManager.getLogger();

  private static final String TABLE_NAME = "groups";

  private final ACI aci;

  public GroupsTable(ACI aci) { this.aci = aci; }

  @Override
  public Optional<IGroup> get(GroupIdentifier identifier) throws SQLException, InvalidInputException, InvalidProtocolBufferException {
    var query = "SELECT " + ROWID + ", * FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ? AND " + GROUP_ID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      statement.setBytes(2, identifier.serialize());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get", statement)) {
        return rows.next() ? Optional.of(new Group(rows)) : Optional.empty();
      }
    }
  }

  @Override
  public List<IGroup> getAll() throws SQLException {
    var query = "SELECT " + ROWID + ",* FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_all", statement)) {
        var allGroups = new ArrayList<IGroup>();
        while (rows.next()) {
          try {
            allGroups.add(new Group(rows));
          } catch (InvalidInputException | InvalidProtocolBufferException e) {
            logger.error("error parsing group " + rows.getString(GROUP_ID) + " from database", e);
            Sentry.captureException(e);
          }
        }
        return allGroups;
      }
    }
  }

  public void upsert(GroupMasterKey masterKey, DecryptedGroup decryptedGroup, DistributionId distributionId, int lastAvatarFetch)
      throws SQLException, InvalidInputException, InvalidProtocolBufferException {
    final GroupIdentifier groupId = GroupSecretParams.deriveFromMasterKey(masterKey).getPublicParams().getGroupIdentifier();

    Optional<IGroup> existingGroup;
    try {
      existingGroup = get(groupId);
    } catch (InvalidInputException | InvalidProtocolBufferException e) {
      logger.error("error parsing group " + Base64.encodeBytes(groupId.serialize()) + " from database", e);
      Sentry.captureException(e);
      throw e;
    }

    // There doesn't seem to be a use case for changing a group's distributionId (Android app doesn't do it).
    // Always preserve the existing one if available, or create a new one. We don't use getOrCreateDistributionId,
    // because that results in a db insert; we're going to do a db update very soon anyway.
    final DistributionId distributionIdToSave;
    if (existingGroup.isPresent() && existingGroup.get().getDistributionId() != null) {
      distributionIdToSave = existingGroup.get().getDistributionId();
    } else {
      distributionIdToSave = distributionId != null ? distributionId : DistributionId.create();
    }

    if (existingGroup.isPresent()) {
      DecryptedGroupChange change = GroupChangeReconstruct.reconstructGroupChange(existingGroup.get().getDecryptedGroup(), decryptedGroup);
      List<UUID> removed = DecryptedGroupUtil.removedMembersUuidList(change);

      if (removed.size() > 0) {
        logger.info(removed.size() + " members were removed from group " + existingGroup.get().getIdString() + ". Rotating the DistributionId " + distributionIdToSave);
        try {
          SenderKeyUtil.rotateOurKey(new Account(aci), distributionIdToSave);
        } catch (NoSuchAccountException | ServerNotFoundException | IOException | InvalidProxyException e) {
          logger.error("error rotating sender key for DistributionId " + distributionIdToSave, e);
          Sentry.captureException(e);
        }
      }
    }

    var query = "INSERT INTO " + TABLE_NAME + "(" + ACCOUNT_UUID + "," + GROUP_ID + "," + MASTER_KEY + "," + REVISION + "," + DISTRIBUTION_ID + "," + LAST_AVATAR_FETCH + "," +
                GROUP_INFO + ") VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (" + ACCOUNT_UUID + "," + GROUP_ID + ") DO UPDATE SET " + REVISION + "=excluded." + REVISION + "," +
                DISTRIBUTION_ID + "=excluded." + DISTRIBUTION_ID + "," + LAST_AVATAR_FETCH + "=excluded." + LAST_AVATAR_FETCH + "," + GROUP_INFO + "=excluded." + GROUP_INFO;
    try (var statement = Database.getConn().prepareStatement(query)) {
      int i = 1;
      statement.setString(i++, aci.toString());
      statement.setBytes(i++, groupId.serialize());
      statement.setBytes(i++, masterKey.serialize());
      statement.setInt(i++, decryptedGroup.getRevision());
      statement.setString(i++, distributionIdToSave.toString());
      statement.setInt(i++, lastAvatarFetch);
      statement.setBytes(i++, decryptedGroup.toByteArray());
      Database.executeUpdate(TABLE_NAME + "_upsert", statement);
    }
  }

  @Override
  public File getGroupAvatarFile(GroupIdentifier groupId) {
    return new File(groupAvatarPath, "group-" + Base64.encodeBytes(groupId.serialize()).replace("/", "_"));
  }

  @Override
  public void deleteAccount(ACI aci) throws SQLException {
    var query = "DELETE FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      Database.executeUpdate(TABLE_NAME + "_delete_account", statement);
    }
  }

  @Override
  public void setGroupAvatarPath(String path) throws IOException {
    groupAvatarPath = path;
    Files.createDirectories(new File(groupAvatarPath).toPath());
  }

  public class Group implements IGroup {
    private final int rowId;
    private final Account account;
    private final GroupMasterKey masterKey;
    private int revision;
    private int lastAvatarFetch;
    private DistributionId distributionId;
    private DecryptedGroup group;

    private Group(ResultSet row) throws SQLException, InvalidInputException, InvalidProtocolBufferException {
      account = new Account(ACI.parseOrThrow(row.getString(ACCOUNT_UUID)));
      rowId = row.getInt(ROWID);
      masterKey = new GroupMasterKey(row.getBytes(MASTER_KEY));
      revision = row.getInt(REVISION);
      lastAvatarFetch = row.getInt(LAST_AVATAR_FETCH);
      String distributionIdString = row.getString(DISTRIBUTION_ID);
      distributionId = distributionIdString == null ? null : DistributionId.from(distributionIdString);
      group = DecryptedGroup.parseFrom(row.getBytes(GROUP_INFO));
    }

    @Override
    public GroupIdentifier getId() {
      return GroupsUtil.GetIdentifierFromMasterKey(masterKey);
    }

    @Override
    public String getIdString() {
      return Base64.encodeBytes(getId().serialize());
    }

    @Override
    public int getRevision() {
      return revision;
    }

    @Override
    public GroupMasterKey getMasterKey() {
      return masterKey;
    }

    @Override
    public GroupSecretParams getSecretParams() {
      return GroupSecretParams.deriveFromMasterKey(masterKey);
    }

    @Override
    public DecryptedGroup getDecryptedGroup() {
      return group;
    }

    @Override
    public void setDecryptedGroup(DecryptedGroup decryptedGroup) throws SQLException {
      var query = "UPDATE " + TABLE_NAME + " SET " + REVISION + " = ?, " + GROUP_INFO + " = ? WHERE " + ROWID + " = ?";
      try (var statement = Database.getConn().prepareStatement(query)) {
        statement.setInt(1, decryptedGroup.getRevision());
        statement.setBytes(2, decryptedGroup.toByteArray());
        statement.setInt(3, rowId);
        Database.executeUpdate(TABLE_NAME + "_set_decrypted_group", statement);
        revision = decryptedGroup.getRevision();
        this.group = decryptedGroup;
      }
    }

    @Override
    public SignalServiceGroupV2 getSignalServiceGroupV2() {
      return SignalServiceGroupV2.newBuilder(masterKey).withRevision(revision).build();
    }

    @Override
    public void delete() throws SQLException {
      var query = "DELETE FROM " + TABLE_NAME + " WHERE " + ROWID + " = ?";
      try (var statement = Database.getConn().prepareStatement(query)) {
        statement.setInt(1, rowId);
        Database.executeUpdate(TABLE_NAME + "_delete", statement);
      }
    }

    @Override
    public JsonGroupV2Info getJsonGroupV2Info() {
      try {
        fetchAvatar();
      } catch (IOException | InvalidProxyException | SQLException | ServerNotFoundException | NoSuchAccountException e) {
        logger.warn("Failed to fetch group avatar: " + e.getMessage());
        logger.debug("stack trace for group avi fetch failure: ", e);
      }
      JsonGroupV2Info jsonGroupV2Info = new JsonGroupV2Info(SignalServiceGroupV2.newBuilder(masterKey).withRevision(revision).build(), group);
      File avatarFile = getGroupAvatarFile(getId());
      if (avatarFile.exists()) {
        jsonGroupV2Info.avatar = avatarFile.getAbsolutePath();
      }
      return jsonGroupV2Info;
    }

    private void fetchAvatar() throws IOException, InvalidProxyException, SQLException, ServerNotFoundException, NoSuchAccountException {
      File avatarFile = getGroupAvatarFile(getId());
      if (lastAvatarFetch == revision) {
        // group avatar has already been downloaded for this revision of the group
        return;
      }
      GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(masterKey);
      GroupsV2Operations.GroupOperations groupOperations = GroupsUtil.GetGroupsV2Operations(account.getServiceConfiguration()).forGroup(groupSecretParams);

      File tmpFile = FileUtil.createTempFile();
      try (InputStream input =
               account.getSignalDependencies().getMessageReceiver().retrieveGroupsV2ProfileAvatar(group.getAvatar(), tmpFile, ServiceConfig.AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE)) {
        byte[] encryptedData = Util.readFully(input);
        byte[] decryptedData = groupOperations.decryptAvatar(encryptedData);
        OutputStream outputStream = new FileOutputStream(avatarFile);
        outputStream.write(decryptedData);
        lastAvatarFetch = revision;
      } catch (NonSuccessfulResponseCodeException e) {
        lastAvatarFetch = revision;
      } finally {
        try {
          Files.delete(tmpFile.toPath());
        } catch (IOException e) {
          logger.warn("Failed to delete received group avatar temp file " + tmpFile + ", ignoring: " + e.getMessage());
        }
      }
    }

    @Override
    public List<Recipient> getMembers() throws IOException, SQLException {
      List<Recipient> recipients = new ArrayList<>();
      for (DecryptedMember member : group.getMembersList()) {
        var recipient = Database.Get(aci).RecipientsTable.get(UuidUtil.fromByteString(member.getUuid()));
        recipients.add(recipient);
      }
      return recipients;
    }

    @Override
    public List<Recipient> getPendingMembers() throws IOException, SQLException {
      List<Recipient> recipients = new ArrayList<>();
      for (DecryptedPendingMember member : group.getPendingMembersList()) {
        Recipient recipient = Database.Get(aci).RecipientsTable.get(UuidUtil.fromByteString(member.getUuid()));
        recipients.add(recipient);
      }
      return recipients;
    }

    @Override
    public List<Recipient> getRequestingMembers() throws IOException, SQLException {
      List<Recipient> recipients = new ArrayList<>();
      for (DecryptedRequestingMember member : group.getRequestingMembersList()) {
        Recipient recipient = Database.Get(aci).RecipientsTable.get(UuidUtil.fromByteString(member.getUuid()));
        recipients.add(recipient);
      }
      return recipients;
    }

    @Override
    public boolean isAdmin(Recipient recipient) {
      for (DecryptedMember member : group.getMembersList()) {
        if (UuidUtil.fromByteString(member.getUuid()).equals(recipient.getUUID())) {
          return member.getRole() == Member.Role.ADMINISTRATOR;
        }
      }
      return false;
    }

    @Override
    public DistributionId getOrCreateDistributionId() throws SQLException {
      if (distributionId == null) {
        distributionId = DistributionId.create();
        var query = "UPDATE " + TABLE_NAME + " SET " + DISTRIBUTION_ID + " = ? WHERE " + ROWID + " = ?";
        try (var statement = Database.getConn().prepareStatement(query)) {
          statement.setString(1, distributionId.toString());
          statement.setInt(2, rowId);
          Database.executeUpdate(TABLE_NAME + "_create_distribution_id", statement);
        }
      }
      return distributionId;
    }

    public DistributionId getDistributionId() { return distributionId; }
  }
}
