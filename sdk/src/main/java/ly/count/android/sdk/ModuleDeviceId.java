package ly.count.android.sdk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.UUID;

public class ModuleDeviceId extends ModuleBase implements OpenUDIDProvider, DeviceIdProvider {
    boolean exitTempIdAfterInit = false;
    boolean cleanupTempIdAfterInit = false;

    ly.count.android.sdk.DeviceId deviceIdInstance;

    DeviceId deviceIdInterface;

    ModuleDeviceId(Countly cly, CountlyConfig config) {
        super(cly, config);
        L.v("[ModuleDeviceId] Initialising");

        boolean customIDWasProvided = (config.deviceID != null);

        if (customIDWasProvided) {
            //if a custom ID was provided, set the correct type
            config.idMode = DeviceIdType.DEVELOPER_SUPPLIED;
        }

        if (config.temporaryDeviceIdEnabled && !customIDWasProvided) {
            //if we want to use temporary ID mode and no developer custom ID is provided
            //then we override that custom ID to set the temporary mode
            config.deviceID = ly.count.android.sdk.DeviceId.temporaryCountlyDeviceId;

            //change also the type to indicate that we will go into the temp ID mode
            //type is dev supplied even for temp ID. todo type would become tempID specific
            config.idMode = DeviceIdType.DEVELOPER_SUPPLIED;
        }

        deviceIdInstance = new ly.count.android.sdk.DeviceId(config.idMode, config.deviceID, config.storageProvider, L, this);

        config.deviceIdProvider = this;

        boolean temporaryDeviceIdIsCurrentlyEnabled = deviceIdInstance.isTemporaryIdModeEnabled();
        L.d("[ModuleDeviceId] [TemporaryDeviceId] Temp ID should be enabled[" + config.temporaryDeviceIdEnabled + "] Currently enabled: [" + temporaryDeviceIdIsCurrentlyEnabled + "]");

        if (temporaryDeviceIdIsCurrentlyEnabled && customIDWasProvided) {
            //if a custom ID was provided and we are still in temporary ID mode
            //it means the we had tempID mode at the previous app end
            //exit tempID after init finished
            L.d("[ModuleDeviceId] [TemporaryDeviceId] Decided we have to exit temporary device ID mode, mode enabled: [" + config.temporaryDeviceIdEnabled + "], custom Device ID Set: [" + customIDWasProvided + "]");

            exitTempIdAfterInit = true;
        } else {
            if (!temporaryDeviceIdIsCurrentlyEnabled) {
                //if temp ID mode is not enabled then there should also be no temp ID requests in the RQ
                //note to perform queue cleanup
                cleanupTempIdAfterInit = true;
            }
        }

        deviceIdInterface = new DeviceId();
    }

    void replaceTempIDWithRealIDinRQ(@NonNull String targetDeviceId) {
        String[] storedRequests = storageProvider.getRequests();
        String temporaryIdTag = "&device_id=" + ly.count.android.sdk.DeviceId.temporaryCountlyDeviceId;
        String newIdTag = "&device_id=" + targetDeviceId;

        boolean foundOne = false;
        for (int a = 0; a < storedRequests.length; a++) {
            if (storedRequests[a].contains(temporaryIdTag)) {
                L.d("[ModuleDeviceId] [exitTemporaryIdMode] Found a tag to replace in: [" + storedRequests[a] + "]");
                storedRequests[a] = storedRequests[a].replace(temporaryIdTag, newIdTag);
                foundOne = true;
            }
        }

        if (foundOne) {
            storageProvider.replaceRequests(storedRequests);
        }
    }

    void exitTemporaryIdMode(@NonNull String deviceId) {
        L.d("[ModuleDeviceId] Calling exitTemporaryIdMode");

        if (!_cly.isInitialized()) {
            throw new IllegalStateException("init must be called before exitTemporaryIdMode");
        }

        //start by changing stored ID
        deviceIdInstance.changeToCustomId(deviceId);//run init because not clear if types other then dev supplied can be provided

        //update stored request for ID change to use this new ID
        replaceTempIDWithRealIDinRQ(deviceId);

        //update remote config_ values if automatic update is enabled
        _cly.moduleRemoteConfig.clearValueStoreInternal();
        if (_cly.moduleRemoteConfig.remoteConfigAutomaticUpdateEnabled && consentProvider.anyConsentGiven()) {
            _cly.moduleRemoteConfig.updateRemoteConfigValues(null, null, false, null);
        }

        _cly.requestQueue().attemptToSendStoredRequests();
    }

    /**
     * Changes current device id type to the one specified in parameter. Closes current session and
     * reopens new one with new id. Doesn't merge user profiles on the server
     *
     * @param deviceId Optional device ID for a case when type = DEVELOPER_SPECIFIED
     */
    void changeDeviceIdWithoutMergeInternal(@NonNull String deviceId) {
        if (isTemporaryIdEnabled() && deviceId.equals(ly.count.android.sdk.DeviceId.temporaryCountlyDeviceId)) {
            // we already are in temporary mode and we want to set temporary mode
            // in this case we just ignore the request since nothing has to be done
            return;
        }

        if (deviceIdInstance.getCurrentId().equals(deviceId)) {
            //if we are attempting to change the device ID to the same ID, do nothing
            L.w("[ModuleDeviceId] changeDeviceIdWithoutMergeInternal, We are attempting to change the device ID to the same ID, request will be ignored");
            return;
        }

        if (isTemporaryIdEnabled() || requestQueueProvider.queueContainsTemporaryIdItems()) {
            // we are about to exit temporary ID mode
            // because of the previous check, we know that the new type is a different one
            // we just call our method for exiting it
            // we don't end the session, we just update the device ID and connection queue
            exitTemporaryIdMode(deviceId);
        }

        // we are either making a simple ID change or entering temporary mode
        // in both cases we act the same as the temporary ID requests will be updated with the final ID later

        //force flush events so that they are associated correctly
        _cly.moduleRequestQueue.sendEventsIfNeeded(true);

        //update remote config_ values after id change if automatic update is enabled
        _cly.moduleRemoteConfig.clearAndDownloadAfterIdChange();

        _cly.moduleSessions.endSessionInternal(getDeviceId());

        //remove all consent
        _cly.moduleConsent.removeConsentAllInternal(ModuleConsent.ConsentChangeSource.DeviceIDChangedNotMerged);

        if (deviceId.equals(ly.count.android.sdk.DeviceId.temporaryCountlyDeviceId)) {
            // entering temp ID mode
            deviceIdInstance.enterTempIDMode();
        } else {
            // setting a custom device ID
            deviceIdInstance.changeToCustomId(deviceId);
        }

        //clear automated star rating session values because now we have a new user
        _cly.moduleRatings.clearAutomaticStarRatingSessionCountInternal();
    }

    /**
     * Changes current device id to the one specified in parameter. Merges user profile with new id
     * (if any) with old profile.
     *
     * @param deviceId new device id
     */
    void changeDeviceIdWithMergeInternal(@NonNull String deviceId) {
        if ("".equals(deviceId)) {
            L.e("changeDeviceIdWithMergeInternal, provided device ID can't be empty string");
            return;
        }

        //todo finish implementing remaining changes and uncomment this
        //if(deviceIdInstance.getCurrentId().equals(deviceId)) {
        //    //if we are attempting to change the device ID to the same ID, do nothing
        //    L.w("[ModuleDeviceId] changeDeviceIdWithMergeInternal, We are attempting to change the device ID to the same ID, request will be ignored");
        //    return;
        //}

        if (isTemporaryIdEnabled() || requestQueueProvider.queueContainsTemporaryIdItems()) {
            //if we are in temporary ID mode or
            //at some moment have enabled temporary mode

            if (deviceId.equals(ly.count.android.sdk.DeviceId.temporaryCountlyDeviceId)) {
                //if we want to enter temporary ID mode
                //just exit, nothing to do

                L.w("[ModuleDeviceId, changeDeviceId] About to enter temporary ID mode when already in it");

                return;
            }

            // if a developer supplied ID is provided
            //we just exit this mode and set the id to the provided one
            exitTemporaryIdMode(deviceId);
        } else {
            //we are not in temporary mode, nothing special happens
            // we are either making a simple ID change or entering temporary mode
            // in both cases we act the same as the temporary ID requests will be updated with the final ID later

            //update remote config_ values after id change if automatic update is enabled
            _cly.moduleRemoteConfig.clearAndDownloadAfterIdChange();

            requestQueueProvider.changeDeviceId(deviceId, _cly.moduleSessions.roundedSecondsSinceLastSessionDurationUpdate());
        }
    }

    @SuppressWarnings("deprecation")
    static DeviceIdType fromOldDeviceIdToNew(@NonNull ly.count.android.sdk.DeviceId.Type oldType) {
        switch (oldType) {
            case ADVERTISING_ID:
                return DeviceIdType.ADVERTISING_ID;
            case OPEN_UDID:
                return DeviceIdType.OPEN_UDID;
            case TEMPORARY_ID:
                return DeviceIdType.TEMPORARY_ID;
            case DEVELOPER_SUPPLIED:
                return DeviceIdType.DEVELOPER_SUPPLIED;
        }
        //should not reach this far, but in that case say it's developer supplied
        return DeviceIdType.DEVELOPER_SUPPLIED;
    }

    @SuppressWarnings("deprecation")
    static ly.count.android.sdk.DeviceId.Type fromNewDeviceIdToOld(@NonNull DeviceIdType newType) {
        switch (newType) {
            case ADVERTISING_ID:
                return ly.count.android.sdk.DeviceId.Type.ADVERTISING_ID;
            case OPEN_UDID:
                return ly.count.android.sdk.DeviceId.Type.OPEN_UDID;
            case TEMPORARY_ID:
                return ly.count.android.sdk.DeviceId.Type.TEMPORARY_ID;
            case DEVELOPER_SUPPLIED:
                return ly.count.android.sdk.DeviceId.Type.DEVELOPER_SUPPLIED;
        }
        //should not reach this far, but in that case say it's developer supplied
        return ly.count.android.sdk.DeviceId.Type.DEVELOPER_SUPPLIED;
    }

    @Override
    public void initFinished(@NonNull CountlyConfig config) {
        if (exitTempIdAfterInit) {
            L.i("[ModuleDeviceId, initFinished] Exiting temp ID at the end of init");
            exitTemporaryIdMode(config.deviceID);
        } else if (cleanupTempIdAfterInit) {
            L.i("[ModuleDeviceId, initFinished] Cleaning up potentially left temp ID requests in queue");
            String storedDevId = getDeviceId();

            if (storedDevId != null && !storedDevId.isEmpty()) {
                replaceTempIDWithRealIDinRQ(storedDevId);
            } else {
                L.w("[ModuleDeviceId, initFinished] Can't cleanup RQ, device ID is either null or empty [" + storedDevId + "]");
            }
        }
    }

    @Override
    void halt() {

    }

    public final static String PREF_KEY = "openudid";
    public final static String PREFS_NAME = "openudid_prefs";

    @SuppressLint("HardwareIds")
    @Override @NonNull public String getOpenUDID() {
        String retrievedID;

        SharedPreferences mPreferences = _cly.context_.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        //Try to get the openudid from local preferences
        retrievedID = mPreferences.getString(PREF_KEY, null);
        if (retrievedID == null) //Not found if temp storage
        {
            Countly.sharedInstance().L.d("[OpenUDID] Generating openUDID");
            //Try to get the ANDROID_ID
            retrievedID = Settings.Secure.getString(_cly.context_.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (retrievedID == null || retrievedID.equals("9774d56d682e549c") || retrievedID.length() < 15) {
                //if ANDROID_ID is null, or it's equals to the GalaxyTab generic ANDROID_ID or is too short bad, generates a new one
                //the new one would be random
                retrievedID = UUID.randomUUID().toString();
            }

            final SharedPreferences.Editor e = mPreferences.edit();
            e.putString(PREF_KEY, retrievedID);
            e.apply();
        }

        Countly.sharedInstance().L.d("[OpenUDID] ID: " + retrievedID);

        return retrievedID;
    }

    @Override public @Nullable String getDeviceId() {
        return deviceIdInstance.getCurrentId();
    }

    @Override public boolean isTemporaryIdEnabled() {
        return deviceIdInstance.isTemporaryIdModeEnabled();
    }

    @Override public @NonNull ly.count.android.sdk.DeviceId getDeviceIdInstance() {
        return deviceIdInstance;
    }

    public class DeviceId {
        /**
         * Changes current device id to the one specified in parameter. Closes current session and
         * reopens new one with new id. Doesn't merge user profiles on the server
         *
         * @param deviceId New device ID
         */
        public void changeWithoutMerge(@Nullable String deviceId) {
            synchronized (_cly) {
                L.d("[DeviceId] Calling 'changeDeviceIdWithoutMerge'");

                if (deviceId == null) {
                    L.e("[DeviceId] changeDeviceIdWithoutMerge, provided device ID value was 'null'. Request will be ignored");
                    return;
                }

                changeDeviceIdWithoutMergeInternal(deviceId);
            }
        }

        /**
         * Changes current device id to the one specified in parameter. Merges user profile with new id
         * (if any) with old profile.
         *
         * @param deviceId new device id
         */
        public void changeWithMerge(@Nullable String deviceId) {
            synchronized (_cly) {
                L.d("[DeviceId] Calling 'changeDeviceIdWithMerge'");

                if (deviceId == null) {
                    L.e("[DeviceId] changeDeviceIdWithMerge, provided device ID value was 'null'. Request will be ignored");
                    return;
                }

                changeDeviceIdWithMergeInternal(deviceId);
            }
        }

        /**
         * Returns the device id used by countly for this device
         *
         * @return device ID
         */
        public String getID() {
            synchronized (_cly) {
                L.d("[DeviceId] Calling 'getDeviceID'");

                return getDeviceId();
            }
        }

        /**
         * Returns the type of the device ID used by countly for this device.
         *
         * @return device ID type
         */
        public DeviceIdType getType() {
            synchronized (_cly) {
                L.d("[DeviceId] Calling 'getDeviceIDType'");

                return deviceIdInstance.getType();
            }
        }

        /**
         * Go into temporary device ID mode
         */
        public void enableTemporaryIdMode() {
            synchronized (_cly) {
                L.i("[DeviceId] Calling 'enableTemporaryIdMode'");

                changeDeviceIdWithoutMergeInternal(ly.count.android.sdk.DeviceId.temporaryCountlyDeviceId);
            }
        }
    }
}
