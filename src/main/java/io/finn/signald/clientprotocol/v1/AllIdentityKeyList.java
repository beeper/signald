/*
 * Copyright (C) 2021 Finn Herzfeld
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.finn.signald.clientprotocol.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.finn.signald.db.IdentityKeysTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

public class AllIdentityKeyList {
  @JsonProperty("identity_keys") List<IdentityKeyList> identityKeys;

  public AllIdentityKeyList(SignalServiceAddress ownAddress, org.whispersystems.libsignal.IdentityKey ownKey, List<IdentityKeysTable.IdentityKeyRow> entireIdentityDB) {
    Map<String, IdentityKeyList> keyMap = new HashMap<>();
    for (IdentityKeysTable.IdentityKeyRow row : entireIdentityDB) {
      if (!keyMap.containsKey(row.getAddress().toString())) {
        keyMap.put(row.getAddress().toString(), new IdentityKeyList(ownAddress, ownKey, row.getAddress(), null));
      }
      keyMap.get(row.getAddress().toString()).addKey(row);
    }
    identityKeys = new ArrayList<>(keyMap.values());
  }
}