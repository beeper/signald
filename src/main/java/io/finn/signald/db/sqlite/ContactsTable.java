package io.finn.signald.db.sqlite;

import io.finn.signald.db.Database;
import io.finn.signald.db.IContactsTable;
import io.finn.signald.db.IRecipientsTable;
import io.finn.signald.db.Recipient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.signal.libsignal.protocol.util.Pair;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

public class ContactsTable implements IContactsTable {
  private static final String TABLE_NAME = "contacts";
  private final String RECIPIENT_ACI = "uuid";
  private final String RECIPIENT_E164 = "e164";

  private final ACI aci;

  public ContactsTable(ACI aci) { this.aci = aci; }

  public ACI getACI() { return aci; }

  private ContactInfo infoFromRow(ResultSet row) throws SQLException {
    var serviceAddress = new SignalServiceAddress(ACI.from(UUID.fromString(row.getString(RECIPIENT_ACI))), row.getString(RECIPIENT_E164));
    return new ContactInfo(row.getString(NAME), new Recipient(aci.uuid(), row.getInt(RECIPIENT), serviceAddress), row.getString(COLOR), row.getBytes(PROFILE_KEY),
                           row.getInt(MESSAGE_EXPIRATION_TIME), row.getInt(INBOX_POSITION));
  }

  @Override
  public ContactInfo get(Recipient recipient) throws SQLException {
    var query = String.format("SELECT %s, %s.%s, %s.%s, %s, %s, %s, %s, %s FROM %s JOIN %s ON %s.%s = %s.%s WHERE %s.%s=? AND %s=?",
                              // FIELDS
                              RECIPIENT, RecipientsTable.TABLE_NAME, RECIPIENT_ACI, RecipientsTable.TABLE_NAME, RECIPIENT_E164, NAME, COLOR, PROFILE_KEY, MESSAGE_EXPIRATION_TIME,
                              INBOX_POSITION,
                              // FROM
                              TABLE_NAME,
                              // JOIN
                              RecipientsTable.TABLE_NAME, TABLE_NAME, RECIPIENT, RecipientsTable.TABLE_NAME, IRecipientsTable.ROW_ID,
                              // WHERE
                              TABLE_NAME, ACCOUNT_UUID, RECIPIENT);
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setObject(1, aci.uuid());
      statement.setInt(2, recipient.getId());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get", statement)) {
        return rows.next() ? infoFromRow(rows) : null;
      }
    }
  }

  @Override
  public ArrayList<ContactInfo> getAll() throws SQLException {
    var query = String.format("SELECT %s, %s.%s, %s.%s, %s, %s, %s, %s, %s FROM %s JOIN %s ON %s.%s = %s.%s WHERE %s.%s=?",
                              // FIELDS
                              RECIPIENT, RecipientsTable.TABLE_NAME, RECIPIENT_ACI, RecipientsTable.TABLE_NAME, RECIPIENT_E164, NAME, COLOR, PROFILE_KEY, MESSAGE_EXPIRATION_TIME,
                              INBOX_POSITION,
                              // FROM
                              TABLE_NAME,
                              // JOIN
                              RecipientsTable.TABLE_NAME, TABLE_NAME, RECIPIENT, RecipientsTable.TABLE_NAME, IRecipientsTable.ROW_ID,
                              // WHERE
                              TABLE_NAME, ACCOUNT_UUID);
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setObject(1, aci.uuid());
      try (var rows = Database.executeQuery(TABLE_NAME + "_get", statement)) {
        var contactInfos = new ArrayList<ContactInfo>();
        while (rows.next()) {
          contactInfos.add(infoFromRow(rows));
        }
        return contactInfos;
      }
    }
  }

  @Override
  public ContactInfo update(Recipient recipient, String name, String color, byte[] profileKey, Integer messageExpirationTime, Integer inboxPosition) throws SQLException {
    var updates = new ArrayList<Pair<String, Object>>();
    if (messageExpirationTime != null) {
      updates.add(new Pair<>(MESSAGE_EXPIRATION_TIME, messageExpirationTime));
    }
    if (name != null) {
      updates.add(new Pair<>(NAME, name));
    }
    if (color != null) {
      updates.add(new Pair<>(COLOR, color));
    }
    if (profileKey != null) {
      updates.add(new Pair<>(PROFILE_KEY, profileKey));
    }
    if (inboxPosition != null) {
      updates.add(new Pair<>(INBOX_POSITION, inboxPosition));
    }

    // First, upsert
    var fieldStr = updates.stream().map(Pair::first).collect(Collectors.joining(","));
    var valueStr = String.join(",", Collections.nCopies(updates.size(), "?"));
    var setStr = updates.stream().map(p -> String.format("%s=EXCLUDED.%s", p.first(), p.first())).collect(Collectors.joining(","));
    var upsertQuery = String.format("INSERT INTO %s (%s, %s, %s) VALUES (?, ?, %s)"
                                        + " ON CONFLICT (%s, %s) DO UPDATE SET %s"
                                        + " RETURNING %s, %s, %s, %s, %s, %s",
                                    // INSERT INTO
                                    TABLE_NAME, ACCOUNT_UUID, RECIPIENT, fieldStr, valueStr,
                                    // ON CONFLICT
                                    ACCOUNT_UUID, RECIPIENT,
                                    // DO UPDATE SET
                                    setStr,
                                    // RETURNING
                                    RECIPIENT, NAME, COLOR, PROFILE_KEY, MESSAGE_EXPIRATION_TIME, INBOX_POSITION);

    String retrievedName;
    int retrievedRecipient;
    String retrievedColor;
    byte[] retrievedProfileKey;
    int retrievedMessageExpirationTime;
    int retrievedInboxPosition;
    try (var upsertStatement = Database.getConn().prepareStatement(upsertQuery)) {
      // Account UUID
      upsertStatement.setObject(1, aci.uuid());
      // Recipient ID
      upsertStatement.setInt(2, recipient.getId());
      int i = 3;
      for (var update : updates) {
        upsertStatement.setObject(i++, update.second());
      }

      try (var upsertRows = Database.executeQuery(TABLE_NAME + "_update", upsertStatement)) {
        if (!upsertRows.next()) {
          return null;
        }

        retrievedName = upsertRows.getString(NAME);
        retrievedRecipient = upsertRows.getInt(RECIPIENT);
        retrievedColor = upsertRows.getString(COLOR);
        retrievedProfileKey = upsertRows.getBytes(PROFILE_KEY);
        retrievedMessageExpirationTime = upsertRows.getInt(MESSAGE_EXPIRATION_TIME);
        retrievedInboxPosition = upsertRows.getInt(INBOX_POSITION);
      }
    }

    // Now, add the data about the recipient.
    var recipientDataQuery = String.format("SELECT %s, %s FROM %s WHERE %s=?", RECIPIENT_ACI, RECIPIENT_E164, RecipientsTable.TABLE_NAME, IRecipientsTable.ROW_ID);
    try (var recipientDataStatement = Database.getConn().prepareStatement(recipientDataQuery)) {
      recipientDataStatement.setInt(1, retrievedRecipient);

      try (var recipientDataRows = Database.executeQuery(TABLE_NAME + "_update", recipientDataStatement)) {
        var serviceAddress = recipientDataRows.next()
                                 ? new SignalServiceAddress(ACI.from(UUID.fromString(recipientDataRows.getString(RECIPIENT_ACI))), recipientDataRows.getString(RECIPIENT_E164))
                                 : null;
        return new ContactInfo(retrievedName, new Recipient(aci.uuid(), retrievedRecipient, serviceAddress), retrievedColor, retrievedProfileKey, retrievedMessageExpirationTime,
                               retrievedInboxPosition);
      }
    }
  }

  @Override
  public void addBatch(List<ContactInfo> contacts) throws SQLException {
    for (var contact : contacts) {
      update(contact);
    }
  }

  @Override
  public void clear() throws SQLException {
    var query = String.format("DELETE FROM %s WHERE %s=?", TABLE_NAME, ACCOUNT_UUID);
    try (var statement = Database.getConn().prepareStatement(query)) {
      statement.setObject(1, aci.uuid());
      var deletedCount = Database.executeUpdate(TABLE_NAME + "_clear", statement);
      logger.info("Deleted {} contacts for {}", deletedCount, aci);
    }
  }
}
