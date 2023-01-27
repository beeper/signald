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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 * Stores the latest 500 messages sent by a given user.
 */
public class MessageSendLogStore {
  private static final Logger logger = LogManager.getLogger();
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
