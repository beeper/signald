/*
 * Copyright (C) 2020 Finn Herzfeld
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

package io.finn.signald.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.finn.signald.exceptions.InvalidStorageFileException;
import io.finn.signald.util.JSONHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asamk.signal.util.RandomUtils;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKey;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.util.PhoneNumberFormatter;
import org.whispersystems.util.Base64;

import java.io.File;
import java.io.IOException;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountData {
    public String username;
    public String password;
    public JsonAddress address;
    public int deviceId;
    public String signalingKey;
    public int preKeyIdOffset;
    public int nextSignedPreKeyId;
    public String profileKey;

    public boolean registered;

    public SignalProtocolStore axolotlStore;
    public GroupStore groupStore;
    public ContactStore contactStore;
    public ThreadStore threadStore;

    private static String dataPath;
    private static Logger logger = LogManager.getLogger("AccountData");

    public static AccountData load(File storageFile) throws IOException {
        ObjectMapper mapper = JSONHelper.GetMapper();

        // TODO: Add locking mechanism to prevent two instances of signald from using the same account at the same time.
        AccountData a = mapper.readValue(storageFile, AccountData.class);
        a.validate(); // Storage path passed for exceptions to include in messages
        a.update();
        return a;
    }

    public static void createLinkedAccount(SignalServiceAccountManager.NewDeviceRegistrationReturn registration, String password, int registrationId, String signalingKey) throws InvalidInputException, IOException {
        logger.debug("Creating new local account by linking");
        AccountData a = new AccountData();
        a.address = new JsonAddress(registration.getNumber(), registration.getUuid());
        a.password = password;

        // if the profileKey returned is null, a new one will be generated when we call a.init()
        if(registration.getProfileKey() != null) {
            a.setProfileKey(registration.getProfileKey());
        }

        a.deviceId = registration.getDeviceId();
        a.signalingKey = signalingKey;
        a.axolotlStore = new SignalProtocolStore(registration.getIdentity(), registrationId);
        a.registered = true;
        a.init();
        a.save();
    }

    private void update() {
        if(address == null) {
            address = new JsonAddress(username);
        }
    }

    public void save() throws IOException {
        save(false);
    }

    public void save(boolean allowBlankPassword) throws IOException {
        validate();

        ObjectWriter writer = JSONHelper.GetWriter();

        File dataPathFile = new File(dataPath);
        if(!dataPathFile.exists()) {
            dataPathFile.mkdirs();
        }
        File destination = new File(dataPath + "/" + username);
        logger.debug("Saving to " + destination.toString());
        writer.writeValue(destination, this);
    }

    public void validate() throws InvalidStorageFileException {
        if (!PhoneNumberFormatter.isValidNumber(this.username, null)) {
            throw new InvalidStorageFileException("phone number " + this.username + " is not valid");
        }
    }

    public void init() throws InvalidInputException {
        if(address == null && username != null) {
            address = new JsonAddress(username);
        }

        if(address != null && address.number != null && username == null) {
            username = address.number;
        }

        if(groupStore == null) {
            groupStore = new GroupStore();
        }

        if(contactStore == null) {
            contactStore = new ContactStore();
        }

        if(threadStore == null) {
            threadStore = new ThreadStore();
        }

        if(profileKey == null) {
            generateProfileKey();
        }
    }

    // Generates a profile key if one does not exist
    public void generateProfileKey() throws InvalidInputException {
        if(profileKey == null) {
            byte[] key = new byte[32];
            RandomUtils.getSecureRandom().nextBytes(key);
            setProfileKey(key);
        }
    }

    @JsonIgnore
    public static void setDataPath(String path) {
        dataPath = path + "/data";
    }

    @JsonIgnore
    public ProfileKey getProfileKey() throws IOException, InvalidInputException {
        if(profileKey == null || profileKey.equals("")) {
            return null;
        }
        return new ProfileKey(Base64.decode(profileKey));
    }

    @JsonIgnore
    public void setProfileKey(ProfileKey key) {
        if(key == null) {
            profileKey = "";
        }
        profileKey = Base64.encodeBytes(key.serialize());
    }

    // sets the profile key by bytes, checking for validity first
    @JsonIgnore
    public void setProfileKey(byte[] bytes) throws InvalidInputException {
        setProfileKey(new ProfileKey(bytes));
    }
}
