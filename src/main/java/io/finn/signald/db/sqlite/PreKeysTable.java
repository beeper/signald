/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.db.sqlite;

import io.finn.signald.db.Database;
import io.finn.signald.db.IPreKeysTable;
import io.sentry.Sentry;
import java.sql.SQLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.whispersystems.signalservice.api.push.ACI;

public class PreKeysTable implements IPreKeysTable {
  private static final Logger logger = LogManager.getLogger();

  private static final String TABLE_NAME = "prekeys";
  private static final String ACCOUNT_UUID = "account_uuid";
  private static final String ID = "id";
  private static final String RECORD = "record";

  private final ACI aci;

  public PreKeysTable(ACI aci) { this.aci = aci; }

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    try {
      var query = "SELECT " + RECORD + " FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ? AND " + ID + " = ?";
      try (var statement = Database.getConn().prepareStatement(query)) {
        statement.setString(1, aci.toString());
        statement.setInt(2, preKeyId);
        try (var rows = Database.executeQuery(TABLE_NAME + "_load_pre_key", statement)) {
          if (!rows.next()) {
            throw new InvalidKeyIdException("prekey not found");
          }
          return new PreKeyRecord(rows.getBytes(RECORD));
        }
      }
    } catch (SQLException | InvalidMessageException t) {
      throw new InvalidKeyIdException(t);
    }
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    try {
      var query = "INSERT OR REPLACE INTO " + TABLE_NAME + "(" + ACCOUNT_UUID + "," + ID + "," + RECORD + ") VALUES (?, ?, ?);";
      try (var statement = Database.getConn().prepareStatement(query)) {
        statement.setString(1, aci.toString());
        statement.setInt(2, preKeyId);
        statement.setBytes(3, record.serialize());
        Database.executeUpdate(TABLE_NAME + "_store_pre_key", statement);
      }
    } catch (SQLException e) {
      logger.error("failed to store prekey", e);
      Sentry.captureException(e);
    }
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    try {
      var query = "SELECT " + RECORD + " FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ? AND " + ID + " = ?";
      try (var statement = Database.getConn().prepareStatement(query)) {
        statement.setString(1, aci.toString());
        statement.setInt(2, preKeyId);
        try (var rows = Database.executeQuery(TABLE_NAME + "_contains_pre_key", statement)) {
          return rows.next();
        }
      }
    } catch (SQLException e) {
      logger.error("failed to check if prekey exists", e);
      Sentry.captureException(e);
      return false;
    }
  }

  @Override
  public void removePreKey(int preKeyId) {
    try {
      var query = "DELETE FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ? AND " + ID + " = ?";
      try (var statement = Database.getConn().prepareStatement(query)) {
        statement.setString(1, aci.toString());
        statement.setInt(2, preKeyId);
        Database.executeUpdate(TABLE_NAME + "_remove_pre_key", statement);
      }
    } catch (SQLException e) {
      logger.error("failed to delete prekey", e);
      Sentry.captureException(e);
    }
  }

  @Override
  public void deleteAccount(ACI aci) throws SQLException {
    var query = "DELETE FROM " + TABLE_NAME + " WHERE " + ACCOUNT_UUID + " = ?";
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setString(1, aci.toString());
      Database.executeUpdate(TABLE_NAME + "_delete_account", statement);
    }
  }
}
