/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.db;

import io.finn.signald.Account;
import io.finn.signald.BuildConfig;
import io.finn.signald.clientprotocol.v1.JsonAddress;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import io.finn.signald.storage.AccountData;
import io.finn.signald.util.AddressUtil;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.util.DynamicCredentialsProvider;

public class AccountsTable {
  private static final Logger logger = LogManager.getLogger();
  private static final String TABLE_NAME = "accounts";
  private static final String UUID = "uuid";
  private static final String E164 = "e164";
  private static final String FILENAME = "filename";
  private static final String SERVER = "server";

  public static File getFile(ACI aci) throws SQLException, NoSuchAccountException {
    var query = "SELECT " + FILENAME + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_file_aci", statement)) {
        if (!rows.next()) {
          throw new NoSuchAccountException(aci.toString());
        }
        return new File(rows.getString(FILENAME));
      }
    }
  }

  public static File getFile(String e164) throws SQLException, NoSuchAccountException {
    var query = "SELECT " + FILENAME + " FROM " + TABLE_NAME + " WHERE " + E164 + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, e164);
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_file_e164", statement)) {
        if (!rows.next()) {
          throw new NoSuchAccountException(e164);
        }
        return new File(rows.getString(FILENAME));
      }
    }
  }

  public static void add(String e164, ACI aci, String filename, java.util.UUID server) throws SQLException {
    var query = "INSERT OR IGNORE INTO " + TABLE_NAME + " (" + UUID + "," + E164 + "," + FILENAME + "," + SERVER + ") VALUES (?, ?, ?, ?)";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      if (e164 != null) {
        statement.setString(2, e164);
      }
      statement.setString(3, filename);
      statement.setString(4, server == null ? null : server.toString());
      Database.executeUpdate(TABLE_NAME + "_add", statement);
      AddressUtil.addKnownAddress(new SignalServiceAddress(aci, e164));
    }
  }

  public static void importFromJSON(File f) throws IOException, SQLException {
    AccountData accountData = AccountData.load(f);
    if (accountData.getUUID() == null) {
      logger.warn("unable to import account with no UUID: " + accountData.getLegacyUsername());
      return;
    }
    logger.info("migrating account if needed: " + accountData.address.toRedactedString());
    add(accountData.getLegacyUsername(), accountData.address.getACI(), f.getAbsolutePath(), java.util.UUID.fromString(BuildConfig.DEFAULT_SERVER_UUID));
    boolean needsSave = false;
    Account account = new Account(accountData.getUUID());
    try {
      if (accountData.legacyProtocolStore != null) {
        accountData.legacyProtocolStore.migrateToDB(account);
        accountData.legacyProtocolStore = null;
        needsSave = true;
      }
      if (accountData.legacyRecipientStore != null) {
        accountData.legacyRecipientStore.migrateToDB(account);
        accountData.legacyRecipientStore = null;
        needsSave = true;
      }

      if (accountData.legacyBackgroundActionsLastRun != null) {
        accountData.legacyBackgroundActionsLastRun.migrateToDB(account);
        accountData.legacyBackgroundActionsLastRun = null;
        needsSave = true;
      }

      if (accountData.legacyGroupsV2 != null) {
        needsSave = accountData.legacyGroupsV2.migrateToDB(account) || needsSave;
      }

      needsSave = accountData.migrateToDB(account) || needsSave;
    } finally {
      if (needsSave) {
        accountData.save();
      }
    }
  }

  public static void deleteAccount(java.util.UUID uuid) throws SQLException {
    var query = "DELETE FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, uuid.toString());
      Database.executeUpdate(TABLE_NAME + "_delete", statement);
    }
  }

  public static void setUUID(JsonAddress address) throws SQLException {
    assert address.uuid != null;
    assert address.number != null;
    var query = "UPDATE " + TABLE_NAME + " SET " + UUID + " = ? WHERE " + E164 + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, address.uuid);
      statement.setString(2, address.number);
      Database.executeUpdate(TABLE_NAME + "_set_uuid", statement);
    }
  }

  public static java.util.UUID getUUID(String e164) throws SQLException, NoSuchAccountException { return getACI(e164).uuid(); }

  public static ACI getACI(String e164) throws SQLException, NoSuchAccountException {
    var query = "SELECT " + UUID + " FROM " + TABLE_NAME + " WHERE " + E164 + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, e164);
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_aci", statement)) {
        if (!rows.next()) {
          throw new NoSuchAccountException(e164);
        }
        return ACI.from(java.util.UUID.fromString(rows.getString(UUID)));
      }
    }
  }

  public static String getE164(ACI aci) throws SQLException, NoSuchAccountException {
    var query = "SELECT " + E164 + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_e164", statement)) {
        if (!rows.next()) {
          throw new NoSuchAccountException(aci.toString());
        }
        return rows.getString(E164);
      }
    }
  }

  public static ServersTable.Server getServer(java.util.UUID uuid) throws SQLException, IOException, ServerNotFoundException, InvalidProxyException {
    return getServer(ACI.from(uuid));
  }

  public static ServersTable.Server getServer(ACI aci) throws SQLException, IOException, ServerNotFoundException, InvalidProxyException {
    var query = "SELECT " + SERVER + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_server", statement)) {
        if (!rows.next()) {
          throw new AssertionError("account not found");
        }
        String serverUUID = rows.getString(SERVER);
        if (serverUUID == null) {
          serverUUID = BuildConfig.DEFAULT_SERVER_UUID;
          setServer(aci, serverUUID);
        }
        return ServersTable.getServer(java.util.UUID.fromString(serverUUID));
      }
    }
  }

  private static void setServer(ACI aci, String server) throws SQLException {
    var query = "UPDATE " + TABLE_NAME + " SET " + SERVER + " = ? WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, server);
      statement.setString(2, aci.toString());
      Database.executeUpdate(TABLE_NAME + "_set_server", statement);
    }
  }

  public static DynamicCredentialsProvider getCredentialsProvider(ACI aci) throws SQLException {
    var query = "SELECT " + E164 + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      Account account = new Account(aci);
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_credentials_provider", statement)) {
        if (!rows.next()) {
          throw new AssertionError("account not found");
        }
        String e164 = rows.getString(E164);
        return new DynamicCredentialsProvider(account.getACI(), e164, account.getPassword(), account.getDeviceId());
      }
    }
  }

  public static boolean exists(java.util.UUID uuid) throws SQLException { return exists(ACI.from(uuid)); }

  public static boolean exists(ACI aci) throws SQLException {
    var query = "SELECT " + UUID + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      try (var rows = Database.executeQuery(TABLE_NAME + "_check_exists", statement)) {
        return rows.next();
      }
    }
  }

  public static List<java.util.UUID> getAll() throws SQLException {
    var query = "SELECT " + UUID + " FROM " + TABLE_NAME;
    try (var statement = Database.getConn().prepareStatement(query)) {
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_all", statement)) {
        List<java.util.UUID> results = new ArrayList<>();
        while (rows.next()) {
          results.add(java.util.UUID.fromString(rows.getString(UUID)));
        }
        return results;
      }
    }
  }
}
