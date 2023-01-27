/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.jobs;

import com.google.protobuf.ByteString;
import io.finn.signald.Account;
import io.finn.signald.MessageReceiver;
import io.finn.signald.MessageSendLogEntry;
import io.finn.signald.db.*;
import io.finn.signald.exceptions.InvalidProxyException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.ServerNotFoundException;
import io.finn.signald.util.UnidentifiedAccessUtil;
import java.io.IOException;
import java.sql.SQLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;

public class ResendMessageJob implements Job {
  private static final Logger logger = LogManager.getLogger();

  private final Account account;
  private final String recipientId;
  private final long timestamp;
  private final MessageSendLogEntry messageSendLogEntry;

  public ResendMessageJob(Account account, String recipientId, long timestamp, MessageSendLogEntry entry) {
    this.account = account;
    this.recipientId = recipientId;
    this.timestamp = timestamp;
    this.messageSendLogEntry = entry;
  }

  @Override
  public void run() throws NoSuchAccountException, SQLException, ServerNotFoundException, IOException, InvalidProxyException, InvalidKeyException, UntrustedIdentityException,
                           InvalidInputException {
    var recipient = Database.Get(account.getACI()).RecipientsTable.get(recipientId);

    if (!recipient.isRegistered()) {
      logger.warn("recipient {} is unregistered, not resending message", recipient.getId());
      return;
    }

    var messageSender = account.getSignalDependencies().getMessageSender();
    var access = new UnidentifiedAccessUtil(account.getACI()).getAccessPairFor(recipient);

    if (messageSendLogEntry.getGroupId().isEmpty()) {
      var result = messageSender.resendContent(recipient.getAddress(), access, timestamp, messageSendLogEntry.getContent(), ContentHint.DEFAULT,
                                               messageSendLogEntry.getGroupId().map(g -> g.serialize()), true);
      if (!result.isSuccess()) {
        logger.warn("failed to resend message to {}", recipient.getId());
      }
      return;
    }

    var groupId = messageSendLogEntry.getGroupId().get();
    var groupOptional = Database.Get(account.getACI()).GroupsTable.get(groupId);

    if (groupOptional.isEmpty()) {
      logger.debug("Could not find a matching group for the groupId {}! Skipping message resend.", groupId.toString());
      return;
    } else {
      boolean found = false;
      for (var member : groupOptional.get().getMembers()) {
        if (member.getAddress().getIdentifier().equals(recipient.getAddress().getIdentifier())) {
          found = true;
          break;
        }
      }
      if (!found) {
        logger.warn("The target user {} is no longer in the group {}! Skipping message resend.", recipient.getAddress().getIdentifier(), groupId.toString());
        return;
      }
    }

    final var senderKeyDistributionMessage = messageSender.getOrCreateNewGroupSession(groupOptional.get().getDistributionId());
    final var distributionBytes = ByteString.copyFrom(senderKeyDistributionMessage.serialize());
    final var contentToSend = messageSendLogEntry.getContent().toBuilder().setSenderKeyDistributionMessage(distributionBytes).build();

    var result =
        messageSender.resendContent(recipient.getAddress(), access, timestamp, contentToSend, ContentHint.DEFAULT, messageSendLogEntry.getGroupId().map(g -> g.serialize()), true);
    if (!result.isSuccess()) {
      logger.warn("failed to resend message to {}", recipient.getId());
      return;
    }

    MessageReceiver.broadcastMessageResendSuccess(account.getUUID(), timestamp);
    logger.warn("successfully resent message to {}", recipient.getId());
  }
}
