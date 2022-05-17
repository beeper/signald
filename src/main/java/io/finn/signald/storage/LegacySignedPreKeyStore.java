/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.finn.signald.Account;
import io.finn.signald.db.Database;
import java.io.IOException;
import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.whispersystems.util.Base64;
@Deprecated
@JsonDeserialize(using = LegacySignedPreKeyStore.SignedPreKeyStoreDeserializer.class)
@JsonSerialize(using = LegacySignedPreKeyStore.SignedPreKeyStoreSerializer.class)
public class LegacySignedPreKeyStore implements org.signal.libsignal.protocol.state.SignedPreKeyStore {
  private static final Logger logger = LogManager.getLogger();
  private final Map<Integer, byte[]> store = new HashMap<>();

  @Override
  public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
    try {
      if (!store.containsKey(signedPreKeyId)) {
        throw new InvalidKeyIdException("No such signedprekeyrecord! " + signedPreKeyId);
      }
      return new SignedPreKeyRecord(store.get(signedPreKeyId));
    } catch (InvalidMessageException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public List<SignedPreKeyRecord> loadSignedPreKeys() {
    try {
      List<SignedPreKeyRecord> results = new LinkedList<>();

      for (byte[] serialized : store.values()) {
        results.add(new SignedPreKeyRecord(serialized));
      }

      return results;
    } catch (InvalidMessageException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
    store.put(signedPreKeyId, record.serialize());
  }

  @Override
  public boolean containsSignedPreKey(int signedPreKeyId) {
    return store.containsKey(signedPreKeyId);
  }

  @Override
  public void removeSignedPreKey(int signedPreKeyId) {
    store.remove(signedPreKeyId);
  }

  public void migrateToDB(Account account) {
    logger.info("migrating " + store.size() + " signed pre-keys to the database");
    Iterator<Map.Entry<Integer, byte[]>> iterator = store.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<Integer, byte[]> entry = iterator.next();
      try {
        if (entry.getValue() == null) {
          continue;
        }
        Database.Get(account.getACI()).SignedPreKeysTable.storeSignedPreKey(entry.getKey(), new SignedPreKeyRecord(entry.getValue()));
        iterator.remove();
      } catch (InvalidMessageException e) {
        logger.warn("failed to migrate session record", e);
      }
    }
  }

  public static class SignedPreKeyStoreDeserializer extends JsonDeserializer<LegacySignedPreKeyStore> {
    @Override
    public LegacySignedPreKeyStore deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
      JsonNode node = jsonParser.getCodec().readTree(jsonParser);
      Map<Integer, byte[]> preKeyMap = new HashMap<>();
      if (node.isArray()) {
        for (JsonNode preKey : node) {
          Integer preKeyId = preKey.get("id").asInt();
          try {
            preKeyMap.put(preKeyId, Base64.decode(preKey.get("record").asText()));
          } catch (IOException e) {
            System.out.println(String.format("Error while decoding prekey for: %s", preKeyId));
          }
        }
      }
      LegacySignedPreKeyStore keyStore = new LegacySignedPreKeyStore();
      keyStore.store.putAll(preKeyMap);
      return keyStore;
    }
  }

  public static class SignedPreKeyStoreSerializer extends JsonSerializer<LegacySignedPreKeyStore> {
    @Override
    public void serialize(LegacySignedPreKeyStore jsonPreKeyStore, JsonGenerator json, SerializerProvider serializerProvider) throws IOException {
      json.writeStartArray();
      for (Map.Entry<Integer, byte[]> signedPreKey : jsonPreKeyStore.store.entrySet()) {
        json.writeStartObject();
        json.writeNumberField("id", signedPreKey.getKey());
        json.writeStringField("record", Base64.encodeBytes(signedPreKey.getValue()));
        json.writeEndObject();
      }
      json.writeEndArray();
    }
  }
}
