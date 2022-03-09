package io.finn.signald.db;

import java.sql.SQLException;
import java.util.UUID;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;
import org.whispersystems.signalservice.api.push.DistributionId;

public interface ISenderKeysTable extends SenderKeyStore {
  String ACCOUNT_UUID = "account_uuid";
  String ADDRESS = "address";
  String DEVICE = "device";
  String DISTRIBUTION_ID = "distribution_id";
  String RECORD = "record";
  String CREATED_AT = "created_at";

  long getCreatedTime(SignalProtocolAddress address, UUID distributionId) throws SQLException;
  void deleteAllFor(String address, DistributionId distributionId) throws SQLException;
  void deleteAccount(UUID uuid) throws SQLException;
}