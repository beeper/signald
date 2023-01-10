/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v0;

import io.finn.signald.annotations.Deprecated;
import io.finn.signald.annotations.ExampleValue;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.ServiceId;

@Deprecated(1641027661)
public class JsonSentTranscriptMessage {
  public JsonAddress destination;
  @ExampleValue(ExampleValue.MESSAGE_ID) public long timestamp;
  public long expirationStartTimestamp;
  public JsonDataMessage message;
  public Map<String, Boolean> unidentifiedStatus = new HashMap<>();
  public boolean isRecipientUpdate;

  JsonSentTranscriptMessage(SentTranscriptMessage s, ACI aci)
      throws IOException, NoSuchAccountException, SQLException, InvalidKeyException, ServerNotFoundException, InvalidProxyException {
    if (s.getDestination().isPresent()) {
      destination = new JsonAddress(s.getDestination().get());
    }
    timestamp = s.getTimestamp();
    expirationStartTimestamp = s.getExpirationStartTimestamp();
    if (s.getDataMessage().isPresent()) {
      message = new JsonDataMessage(s.getDataMessage().get(), aci);
    }
    for (ServiceId r : s.getRecipients()) {
      unidentifiedStatus.put(r.toString(), s.isUnidentified(r));
    }
    isRecipientUpdate = s.isRecipientUpdate();
  }
}
