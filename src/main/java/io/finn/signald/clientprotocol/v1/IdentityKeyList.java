/*
 * Copyright 2022 signald contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * See included LICENSE file
 *
 */

package io.finn.signald.clientprotocol.v1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.finn.signald.annotations.Doc;
import io.finn.signald.db.IIdentityKeysTable;
import io.finn.signald.db.Recipient;
import io.finn.signald.util.SafetyNumberHelper;
import java.util.ArrayList;
import java.util.List;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;

@Doc("a list of identity keys associated with a particular address")
public class IdentityKeyList {
  @JsonIgnore private final Recipient self;
  @JsonIgnore private final org.signal.libsignal.protocol.IdentityKey ownKey;
  @JsonIgnore private final Recipient recipient;

  public final JsonAddress address;
  public final List<IdentityKey> identities = new ArrayList<>();

  public IdentityKeyList(Recipient self, org.signal.libsignal.protocol.IdentityKey ownKey, Recipient recipient, List<IIdentityKeysTable.IdentityKeyRow> identities) {
    this.self = self;
    this.ownKey = ownKey;
    this.address = new JsonAddress(recipient);
    this.recipient = recipient;
    if (identities == null) {
      return;
    }
    identities.forEach(this::addKey);
  }

  public void addKey(IIdentityKeysTable.IdentityKeyRow identity) {
    Fingerprint safetyNumber = SafetyNumberHelper.computeFingerprint(self, ownKey, recipient, identity.getKey());
    if (safetyNumber == null) {
      return;
    }
    identities.add(new IdentityKey(identity, safetyNumber));
  }
}
