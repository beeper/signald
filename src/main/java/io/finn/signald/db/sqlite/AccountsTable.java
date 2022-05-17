/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.db.sqlite;

import io.finn.signald.Account;
import io.finn.signald.BuildConfig;
import io.finn.signald.clientprotocol.v1.JsonAddress;
import io.finn.signald.db.Database;
import io.finn.signald.db.IAccountDataTable;
import io.finn.signald.db.IAccountsTable;
import io.finn.signald.db.IServersTable;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import io.finn.signald.util.AddressUtil;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.util.DynamicCredentialsProvider;

public class AccountsTable implements IAccountsTable {
  private static final String TABLE_NAME = "accounts";

  @Override
  public void add(String e164, ACI aci, java.util.UUID server) throws SQLException {
    var query = "INSERT OR IGNORE INTO " + TABLE_NAME + " (" + UUID + "," + E164 + "," + SERVER + ") VALUES (?, ?, ?)";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      if (e164 != null) {
        statement.setString(2, e164);
      }
      statement.setString(3, server == null ? null : server.toString());
      Database.executeUpdate(TABLE_NAME + "_add", statement);
      AddressUtil.addKnownAddress(new SignalServiceAddress(aci, e164));
    }
  }

  @Override
  public void DeleteAccount(ACI aci, String legacyUsername) throws SQLException {
    Database.getConn().setAutoCommit(false);
    // TODO we should use ON DELETE CASCADE for SQLite as well eventually
    var query = "DELETE FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      Database.executeUpdate(TABLE_NAME + "_delete", statement);
    }
    Database.Get().AccountDataTable.deleteAccount(aci);
    Database.Get(aci).GroupCredentialsTable.deleteAccount(aci);
    Database.Get(aci).GroupsTable.deleteAccount(aci);
    Database.Get(aci).IdentityKeysTable.deleteAccount(aci);
    Database.Get(aci).MessageQueueTable.deleteAccount(legacyUsername);
    Database.Get(aci).PreKeysTable.deleteAccount(aci);
    Database.Get(aci).SessionsTable.deleteAccount(aci);
    Database.Get(aci).RecipientsTable.deleteAccount(aci);
    Database.Get(aci).SenderKeySharedTable.deleteAccount(aci);
    Database.Get(aci).SenderKeysTable.deleteAccount(aci);
    Database.Get(aci).SignedPreKeysTable.deleteAccount(aci);
    Database.getConn().commit();
    Database.getConn().setAutoCommit(true);
  }

  @Override
  public void setUUID(JsonAddress address) throws SQLException {
    if (address.uuid == null || address.number == null) {
      throw new IllegalArgumentException("UUID or number is null");
    }
    var query = "UPDATE " + TABLE_NAME + " SET " + UUID + " = ? WHERE " + E164 + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, address.uuid);
      statement.setString(2, address.number);
      Database.executeUpdate(TABLE_NAME + "_set_uuid", statement);
    }
  }

  @Override
  public ACI getACI(String e164) throws NoSuchAccountException, SQLException {
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

  @Override
  public String getE164(ACI aci) throws NoSuchAccountException, SQLException {
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

  @Override
  public IServersTable.AbstractServer getServer(ACI aci) throws SQLException, IOException, ServerNotFoundException, InvalidProxyException {
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
        return Database.Get().ServersTable.getServer(java.util.UUID.fromString(serverUUID));
      }
    }
  }

  @Override
  public void setServer(ACI aci, String server) throws SQLException {
    var query = "UPDATE " + TABLE_NAME + " SET " + SERVER + " = ? WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, server);
      statement.setString(2, aci.toString());
      Database.executeUpdate(TABLE_NAME + "_set_server", statement);
    }
  }

  @Override
  public DynamicCredentialsProvider getCredentialsProvider(ACI aci) throws SQLException {
    var query = "SELECT " + E164 + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      Account account = new Account(aci);
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_credentials_provider", statement)) {
        if (!rows.next()) {
          throw new AssertionError("account not found");
        }
        String e164 = rows.getString(E164);
        return new DynamicCredentialsProvider(account.getACI(), account.getPNI(), e164, account.getPassword(), account.getDeviceId());
      }
    }
  }

  @Override
  public boolean exists(ACI aci) throws SQLException {
    var query = "SELECT " + UUID + " FROM " + TABLE_NAME + " WHERE " + UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      try (var rows = Database.executeQuery(TABLE_NAME + "_check_exists", statement)) {
        if (!rows.next()) {
          return false;
        }
      }
    }

    Boolean result = Database.Get().AccountDataTable.getBoolean(aci, IAccountDataTable.Key.PENDING_DELETION);
    if (result == null) {
      return true;
    }
    return !result;
  }

  @Override
  public List<ACI> getAll() throws SQLException {
    var query = "SELECT " + UUID + " FROM " + TABLE_NAME;
    try (var statement = Database.getConn().prepareStatement(query)) {
      try (var rows = Database.executeQuery(TABLE_NAME + "_get_all", statement)) {
        List<ACI> results = new ArrayList<>();
        while (rows.next()) {
          results.add(ACI.parseOrThrow(rows.getString(UUID)));
        }
        return results;
      }
    }
  }
}
