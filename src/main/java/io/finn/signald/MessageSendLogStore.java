/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.whispersystems.signalservice.api.push.ACI;

/*
 * Stores the latest 500 messages sent by a given user.
 */
public class MessageSendLogStore {
  private static final Logger logger = LogManager.getLogger();
  private static final ConcurrentHashMap<String, MessageSendLogStore> stores = new ConcurrentHashMap<>();
  public static MessageSendLogStore get(ACI aci) {
    synchronized (stores) {
      if (!stores.containsKey(aci.toString())) {
        var store = new MessageSendLogStore();
        stores.put(aci.toString(), store);
      }
      return stores.get(aci.toString());
    }
  }

  private List<MessageSendLogEntry> lruMessages = new LinkedList<MessageSendLogEntry>();

  public void add(MessageSendLogEntry entry) {
    logger.debug("adding entry to send log with timestamp {}", entry.getTimestamp());
    lruMessages.add(entry);
    if (lruMessages.size() > 500) {
      lruMessages.remove(0);
    }
  }

  public Optional<MessageSendLogEntry> find(long timestamp) { return lruMessages.stream().filter(e -> e.matches(timestamp)).findFirst(); }
}
