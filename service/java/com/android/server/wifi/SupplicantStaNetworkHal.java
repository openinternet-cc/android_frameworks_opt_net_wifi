/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wifi;

import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetworkCallback;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.MutableBoolean;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper class for ISupplicantStaNetwork HAL calls. Gets and sets supplicant sta network variables
 * and interacts with networks.
 * Public fields should be treated as invalid until their 'get' method is called, which will set the
 * value if it returns true
 */
public class SupplicantStaNetworkHal {
    private static final String TAG = "SupplicantStaNetworkHal";
    private static final boolean DBG = false;
    @VisibleForTesting
    public static final String ID_STRING_KEY_FQDN = "fqdn";
    @VisibleForTesting
    public static final String ID_STRING_KEY_CREATOR_UID = "creatorUid";
    @VisibleForTesting
    public static final String ID_STRING_KEY_CONFIG_KEY = "configKey";

    /**
     * Regex pattern for extracting the GSM sim authentication response params from a string.
     * Matches a strings like the following: "[:kc:<kc_value>:sres:<sres_value>]";
     */
    private static final Pattern GSM_AUTH_RESPONSE_PARAMS_PATTERN =
            Pattern.compile(":kc:([0-9a-f]+):sres:([0-9a-f]+)");
    /**
     * Regex pattern for extracting the UMTS sim authentication response params from a string.
     * Matches a strings like the following: ":ik:<ik_value>:ck:<ck_value>:res:<res_value>";
     */
    private static final Pattern UMTS_AUTH_RESPONSE_PARAMS_PATTERN =
            Pattern.compile(":ik:([0-9a-f]+):ck:([0-9a-f]+):res:([0-9a-f]+)");
    /**
     * Regex pattern for extracting the UMTS sim auts response params from a string.
     * Matches a strings like the following: ":<auts_value>";
     */
    private static final Pattern UMTS_AUTS_RESPONSE_PARAMS_PATTERN = Pattern.compile("([0-9a-f]+)");

    private final Object mLock = new Object();
    private ISupplicantStaNetwork mISupplicantStaNetwork = null;
    private final HandlerThread mHandlerThread;
    private int mNetworkId;
    private String mIfaceName;
    private ArrayList<Byte> mSsid;
    private byte[/* 6 */] mBssid;
    private boolean mScanSsid;
    private int mKeyMgmtMask;
    private int mProtoMask;
    private int mAuthAlgMask;
    private int mGroupCipherMask;
    private int mPairwiseCipherMask;
    private String mPskPassphrase;
    private ArrayList<Byte> mWepKey;
    private int mWepTxKeyIdx;
    private boolean mRequirePmf;
    private String mIdStr;
    private int mEapMethod;
    private int mEapPhase2Method;
    private ArrayList<Byte> mEapIdentity;
    private ArrayList<Byte> mEapAnonymousIdentity;
    private ArrayList<Byte> mEapPassword;
    private String mEapCACert;
    private String mEapCAPath;
    private String mEapClientCert;
    private String mEapPrivateKey;
    private String mEapSubjectMatch;
    private String mEapAltSubjectMatch;
    private boolean mEapEngine;
    private String mEapEngineID;
    private String mEapDomainSuffixMatch;

    SupplicantStaNetworkHal(ISupplicantStaNetwork iSupplicantStaNetwork,
                            HandlerThread handlerThread) {
        mISupplicantStaNetwork = iSupplicantStaNetwork;
        mHandlerThread = handlerThread;
    }

    /**
     * Read network variables from wpa_supplicant into the provided WifiConfiguration object.
     *
     * @param config        WifiConfiguration object to be populated.
     * @param networkExtras Map of network extras parsed from wpa_supplicant.
     * @return true if succeeds, false otherwise.
     */
    public boolean loadWifiConfiguration(WifiConfiguration config,
                                         Map<String, String> networkExtras) {
        if (config == null) return false;
        /** SSID */
        config.SSID = null;
        if (getSsid() && !ArrayUtils.isEmpty(mSsid)) {
            config.SSID = NativeUtil.encodeSsid(mSsid);
        } else {
            Log.e(TAG, "failed to read ssid");
            return false;
        }
        /** Network Id */
        config.networkId = -1;
        if (getId()) {
            config.networkId = mNetworkId;
        } else {
            Log.e(TAG, "getId failed");
            return false;
        }
        /** BSSID */
        config.getNetworkSelectionStatus().setNetworkSelectionBSSID(null);
        if (getBssid() && !ArrayUtils.isEmpty(mBssid)) {
            config.getNetworkSelectionStatus().setNetworkSelectionBSSID(
                    NativeUtil.macAddressFromByteArray(mBssid));
        }
        /** Scan SSID (Is Hidden Network?) */
        config.hiddenSSID = false;
        if (getScanSsid()) {
            config.hiddenSSID = mScanSsid;
        }
        /** Require PMF*/
        config.requirePMF = false;
        if (getRequirePmf()) {
            config.requirePMF = mRequirePmf;
        }
        /** WEP keys **/
        config.wepTxKeyIndex = -1;
        if (getWepTxKeyIdx()) {
            config.wepTxKeyIndex = mWepTxKeyIdx;
        }
        for (int i = 0; i < 4; i++) {
            config.wepKeys[i] = null;
            if (getWepKey(i) && !ArrayUtils.isEmpty(mWepKey)) {
                config.wepKeys[i] = NativeUtil.stringFromByteArrayList(mWepKey);
            }
        }
        /** PSK pass phrase */
        config.preSharedKey = null;
        if (getPskPassphrase() && !TextUtils.isEmpty(mPskPassphrase)) {
            config.preSharedKey = mPskPassphrase;
        }
        /** allowedKeyManagement */
        if (getKeyMgmt()) {
            config.allowedKeyManagement =
                    supplicantToWifiConfigurationKeyMgmtMask(mKeyMgmtMask);
        }
        /** allowedProtocols */
        if (getProto()) {
            config.allowedProtocols =
                    supplicantToWifiConfigurationProtoMask(mProtoMask);
        }
        /** allowedAuthAlgorithms */
        if (getAuthAlg()) {
            config.allowedAuthAlgorithms =
                    supplicantToWifiConfigurationAuthAlgMask(mAuthAlgMask);
        }
        /** allowedGroupCiphers */
        if (getGroupCipher()) {
            config.allowedGroupCiphers =
                    supplicantToWifiConfigurationGroupCipherMask(mGroupCipherMask);
        }
        /** allowedPairwiseCiphers */
        if (getPairwiseCipher()) {
            config.allowedPairwiseCiphers =
                    supplicantToWifiConfigurationPairwiseCipherMask(mPairwiseCipherMask);
        }
        /** metadata: idstr */
        if (getIdStr() && !TextUtils.isEmpty(mIdStr)) {
            Map<String, String> metadata = WifiNative.parseNetworkExtra(mIdStr);
            networkExtras.putAll(metadata);
        } else {
            Log.e(TAG, "getIdStr failed");
            return false;
        }
        return loadWifiEnterpriseConfig(config.SSID, config.enterpriseConfig);
    }

    /**
     * Save an entire WifiConfiguration to wpa_supplicant via HIDL.
     *
     * @param config WifiConfiguration object to be saved.
     * @return true if succeeds, false otherwise.
     */
    public boolean saveWifiConfiguration(WifiConfiguration config) {
        if (config == null) return false;
        /** SSID */
        if (config.SSID != null) {
            if (!setSsid(NativeUtil.decodeSsid(config.SSID))) {
                Log.e(TAG, "failed to set SSID: " + config.SSID);
                return false;
            }
        }
        /** BSSID */
        String bssidStr = config.getNetworkSelectionStatus().getNetworkSelectionBSSID();
        if (bssidStr != null) {
            byte[] bssid = NativeUtil.macAddressToByteArray(bssidStr);
            if (!setBssid(bssid)) {
                Log.e(TAG, "failed to set BSSID: " + bssidStr);
                return false;
            }
        }
        /** Pre Shared Key */
        if (config.preSharedKey != null && !setPskPassphrase(config.preSharedKey)) {
            Log.e(TAG, "failed to set psk");
            return false;
        }
        /** Wep Keys */
        boolean hasSetKey = false;
        if (config.wepKeys != null) {
            for (int i = 0; i < config.wepKeys.length; i++) {
                // Prevent client screw-up by passing in a WifiConfiguration we gave it
                // by preventing "*" as a key.
                if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                    if (!setWepKey(i, NativeUtil.stringToByteArrayList(config.wepKeys[i]))) {
                        Log.e(TAG, "failed to set wep_key " + i);
                        return false;
                    }
                    hasSetKey = true;
                }
            }
        }
        /** Wep Tx Key Idx */
        if (hasSetKey) {
            if (!setWepTxKeyIdx(config.wepTxKeyIndex)) {
                Log.e(TAG, "failed to set wep_tx_keyidx: " + config.wepTxKeyIndex);
                return false;
            }
        }
        /** HiddenSSID */
        if (!setScanSsid(config.hiddenSSID)) {
            Log.e(TAG, config.SSID + ": failed to set hiddenSSID: " + config.hiddenSSID);
            return false;
        }
        /** RequirePMF */
        if (!setRequirePmf(config.requirePMF)) {
            Log.e(TAG, config.SSID + ": failed to set requirePMF: " + config.requirePMF);
            return false;
        }
        /** Key Management Scheme */
        if (config.allowedKeyManagement.cardinality() != 0
                && !setKeyMgmt(wifiConfigurationToSupplicantKeyMgmtMask(
                config.allowedKeyManagement))) {
            Log.e(TAG, "failed to set Key Management");
            return false;
        }
        /** Security Protocol */
        if (config.allowedProtocols.cardinality() != 0
                && !setProto(wifiConfigurationToSupplicantProtoMask(config.allowedProtocols))) {
            Log.e(TAG, "failed to set Security Protocol");
            return false;
        }
        /** Auth Algorithm */
        if (config.allowedAuthAlgorithms.cardinality() != 0
                && !setAuthAlg(wifiConfigurationToSupplicantAuthAlgMask(
                config.allowedAuthAlgorithms))) {
            Log.e(TAG, "failed to set AuthAlgorithm");
            return false;
        }
        /** Group Cipher */
        if (config.allowedGroupCiphers.cardinality() != 0
                && !setGroupCipher(wifiConfigurationToSupplicantGroupCipherMask(
                config.allowedGroupCiphers))) {
            Log.e(TAG, "failed to set Group Cipher");
            return false;
        }
        /** Pairwise Cipher*/
        if (config.allowedPairwiseCiphers.cardinality() != 0
                && !setPairwiseCipher(wifiConfigurationToSupplicantPairwiseCipherMask(
                        config.allowedPairwiseCiphers))) {
            Log.e(TAG, "failed to set PairwiseCipher");
            return false;
        }
        /** metadata: FQDN + ConfigKey + CreatorUid */
        final Map<String, String> metadata = new HashMap<String, String>();
        if (config.isPasspoint()) {
            metadata.put(ID_STRING_KEY_FQDN, config.FQDN);
        }
        metadata.put(ID_STRING_KEY_CONFIG_KEY, config.configKey());
        metadata.put(ID_STRING_KEY_CREATOR_UID, Integer.toString(config.creatorUid));
        if (!setIdStr(WifiNative.createNetworkExtra(metadata))) {
            Log.e(TAG, "failed to set id string");
            return false;
        }
        /** UpdateIdentifier */
        if (config.updateIdentifier != null
                && !setUpdateIdentifier(Integer.parseInt(config.updateIdentifier))) {
            Log.e(TAG, "failed to set update identifier");
            return false;
        }
        // Finish here if no EAP config to set
        if (config.enterpriseConfig == null
                || config.enterpriseConfig.getEapMethod() == WifiEnterpriseConfig.Eap.NONE) {
            return true;
        } else {
            return saveWifiEnterpriseConfig(config.SSID, config.enterpriseConfig);
        }
    }

    /**
     * Read network variables from wpa_supplicant into the provided WifiEnterpriseConfig object.
     *
     * @param ssid SSID of the network. (Used for logging purposes only)
     * @param eapConfig WifiEnterpriseConfig object to be populated.
     * @return true if succeeds, false otherwise.
     */
    private boolean loadWifiEnterpriseConfig(String ssid, WifiEnterpriseConfig eapConfig) {
        if (eapConfig == null) return false;
        /** EAP method */
        if (getEapMethod()) {
            eapConfig.setEapMethod(supplicantToWifiConfigurationEapMethod(mEapMethod));
        } else {
            // Invalid eap method could be because it's not an enterprise config.
            Log.e(TAG, "failed to get eap method. Assumimg not an enterprise network");
            return true;
        }
        /** EAP Phase 2 method */
        if (getEapPhase2Method()) {
            eapConfig.setPhase2Method(
                    supplicantToWifiConfigurationEapPhase2Method(mEapPhase2Method));
        } else {
            // We cannot have an invalid eap phase 2 method. Return failure.
            Log.e(TAG, "failed to get eap phase2 method");
            return false;
        }
        /** EAP Identity */
        if (getEapIdentity() && !ArrayUtils.isEmpty(mEapIdentity)) {
            eapConfig.setFieldValue(
                    WifiEnterpriseConfig.IDENTITY_KEY,
                    NativeUtil.stringFromByteArrayList(mEapIdentity));
        }
        /** EAP Anonymous Identity */
        if (getEapAnonymousIdentity() && !ArrayUtils.isEmpty(mEapAnonymousIdentity)) {
            eapConfig.setFieldValue(
                    WifiEnterpriseConfig.ANON_IDENTITY_KEY,
                    NativeUtil.stringFromByteArrayList(mEapAnonymousIdentity));
        }
        /** EAP Password */
        if (getEapPassword() && !ArrayUtils.isEmpty(mEapPassword)) {
            eapConfig.setFieldValue(
                    WifiEnterpriseConfig.PASSWORD_KEY,
                    NativeUtil.stringFromByteArrayList(mEapPassword));
        }
        /** EAP Client Cert */
        if (getEapClientCert() && !TextUtils.isEmpty(mEapClientCert)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.CLIENT_CERT_KEY, mEapClientCert);
        }
        /** EAP CA Cert */
        if (getEapCACert() && !TextUtils.isEmpty(mEapCACert)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.CA_CERT_KEY, mEapCACert);
        }
        /** EAP Subject Match */
        if (getEapSubjectMatch() && !TextUtils.isEmpty(mEapSubjectMatch)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.SUBJECT_MATCH_KEY, mEapSubjectMatch);
        }
        /** EAP Engine ID */
        if (getEapEngineID() && !TextUtils.isEmpty(mEapEngineID)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY, mEapEngineID);
        }
        /** EAP Engine. Set this only if the engine id is non null. */
        if (getEapEngine() && !TextUtils.isEmpty(mEapEngineID)) {
            eapConfig.setFieldValue(
                    WifiEnterpriseConfig.ENGINE_KEY,
                    mEapEngine
                            ? WifiEnterpriseConfig.ENGINE_ENABLE
                            : WifiEnterpriseConfig.ENGINE_DISABLE);
        }
        /** EAP Private Key */
        if (getEapPrivateKey() && !TextUtils.isEmpty(mEapPrivateKey)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, mEapPrivateKey);
        }
        /** EAP Alt Subject Match */
        if (getEapAltSubjectMatch() && !TextUtils.isEmpty(mEapAltSubjectMatch)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.ALTSUBJECT_MATCH_KEY, mEapAltSubjectMatch);
        }
        /** EAP Domain Suffix Match */
        if (getEapDomainSuffixMatch() && !TextUtils.isEmpty(mEapDomainSuffixMatch)) {
            eapConfig.setFieldValue(
                    WifiEnterpriseConfig.DOM_SUFFIX_MATCH_KEY, mEapDomainSuffixMatch);
        }
        /** EAP CA Path*/
        if (getEapCAPath() && !TextUtils.isEmpty(mEapCAPath)) {
            eapConfig.setFieldValue(WifiEnterpriseConfig.CA_PATH_KEY, mEapCAPath);
        }
        return true;
    }

    /**
     * Save network variables from the provided WifiEnterpriseConfig object to wpa_supplicant.
     *
     * @param ssid SSID of the network. (Used for logging purposes only)
     * @param eapConfig WifiEnterpriseConfig object to be saved.
     * @return true if succeeds, false otherwise.
     */
    private boolean saveWifiEnterpriseConfig(String ssid, WifiEnterpriseConfig eapConfig) {
        if (eapConfig == null) return false;
        /** EAP method */
        if (!setEapMethod(wifiConfigurationToSupplicantEapMethod(eapConfig.getEapMethod()))) {
            Log.e(TAG, ssid + ": failed to set eap method: " + eapConfig.getEapMethod());
            return false;
        }
        /** EAP Phase 2 method */
        if (!setEapPhase2Method(wifiConfigurationToSupplicantEapPhase2Method(
                eapConfig.getPhase2Method()))) {
            Log.e(TAG, ssid + ": failed to set eap phase 2 method: " + eapConfig.getPhase2Method());
            return false;
        }
        String eapParam = null;
        /** EAP Identity */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.IDENTITY_KEY);
        if (!TextUtils.isEmpty(eapParam)
                && !setEapIdentity(NativeUtil.stringToByteArrayList(eapParam))) {
            Log.e(TAG, ssid + ": failed to set eap identity: " + eapParam);
            return false;
        }
        /** EAP Anonymous Identity */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.ANON_IDENTITY_KEY);
        if (!TextUtils.isEmpty(eapParam)
                && !setEapAnonymousIdentity(NativeUtil.stringToByteArrayList(eapParam))) {
            Log.e(TAG, ssid + ": failed to set eap anonymous identity: " + eapParam);
            return false;
        }
        /** EAP Password */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.PASSWORD_KEY);
        if (!TextUtils.isEmpty(eapParam)
                && !setEapPassword(NativeUtil.stringToByteArrayList(eapParam))) {
            Log.e(TAG, ssid + ": failed to set eap password");
            return false;
        }
        /** EAP Client Cert */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.CLIENT_CERT_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapClientCert(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap client cert: " + eapParam);
            return false;
        }
        /** EAP CA Cert */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.CA_CERT_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapCACert(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap ca cert: " + eapParam);
            return false;
        }
        /** EAP Subject Match */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.SUBJECT_MATCH_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapSubjectMatch(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap subject match: " + eapParam);
            return false;
        }
        /** EAP Engine ID */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapEngineID(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap engine id: " + eapParam);
            return false;
        }
        /** EAP Engine */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.ENGINE_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapEngine(
                eapParam.equals(WifiEnterpriseConfig.ENGINE_ENABLE) ? true : false)) {
            Log.e(TAG, ssid + ": failed to set eap engine: " + eapParam);
            return false;
        }
        /** EAP Private Key */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapPrivateKey(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap private key: " + eapParam);
            return false;
        }
        /** EAP Alt Subject Match */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.ALTSUBJECT_MATCH_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapAltSubjectMatch(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap alt subject match: " + eapParam);
            return false;
        }
        /** EAP Domain Suffix Match */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.DOM_SUFFIX_MATCH_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapDomainSuffixMatch(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap domain suffix match: " + eapParam);
            return false;
        }
        /** EAP CA Path*/
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.CA_PATH_KEY);
        if (!TextUtils.isEmpty(eapParam) && !setEapCAPath(eapParam)) {
            Log.e(TAG, ssid + ": failed to set eap ca path: " + eapParam);
            return false;
        }

        /** EAP Proactive Key Caching */
        eapParam = eapConfig.getFieldValue(WifiEnterpriseConfig.OPP_KEY_CACHING);
        if (!TextUtils.isEmpty(eapParam)
                && !setEapProactiveKeyCaching(eapParam.equals("1") ? true : false)) {
            Log.e(TAG, ssid + ": failed to set proactive key caching: " + eapParam);
            return false;
        }
        return true;
    }

    /**
     * Maps WifiConfiguration Key Management BitSet to Supplicant HIDL bitmask int
     * TODO(b/32571829): Update mapping when fast transition keys are added
     * @return bitmask int describing the allowed Key Management schemes, readable by the Supplicant
     *         HIDL hal
     */
    private static int wifiConfigurationToSupplicantKeyMgmtMask(BitSet keyMgmt) {
        int mask = 0;
        for (int bit = keyMgmt.nextSetBit(0); bit != -1; bit = keyMgmt.nextSetBit(bit + 1)) {
            switch (bit) {
                case WifiConfiguration.KeyMgmt.NONE:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.NONE;
                    break;
                case WifiConfiguration.KeyMgmt.WPA_PSK:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.WPA_PSK;
                    break;
                case WifiConfiguration.KeyMgmt.WPA_EAP:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.WPA_EAP;
                    break;
                case WifiConfiguration.KeyMgmt.IEEE8021X:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.IEEE8021X;
                    break;
                case WifiConfiguration.KeyMgmt.OSEN:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.OSEN;
                    break;
                case WifiConfiguration.KeyMgmt.FT_PSK:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.FT_PSK;
                    break;
                case WifiConfiguration.KeyMgmt.FT_EAP:
                    mask |= ISupplicantStaNetwork.KeyMgmtMask.FT_EAP;
                    break;
                case WifiConfiguration.KeyMgmt.WPA2_PSK: // This should never happen
                default:
                    throw new IllegalArgumentException(
                            "Invalid protoMask bit in keyMgmt: " + bit);
            }
        }
        return mask;
    }

    private static int wifiConfigurationToSupplicantProtoMask(BitSet protoMask) {
        int mask = 0;
        for (int bit = protoMask.nextSetBit(0); bit != -1; bit = protoMask.nextSetBit(bit + 1)) {
            switch (bit) {
                case WifiConfiguration.Protocol.WPA:
                    mask |= ISupplicantStaNetwork.ProtoMask.WPA;
                    break;
                case WifiConfiguration.Protocol.RSN:
                    mask |= ISupplicantStaNetwork.ProtoMask.RSN;
                    break;
                case WifiConfiguration.Protocol.OSEN:
                    mask |= ISupplicantStaNetwork.ProtoMask.OSEN;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid protoMask bit in wificonfig: " + bit);
            }
        }
        return mask;
    };

    private static int wifiConfigurationToSupplicantAuthAlgMask(BitSet authAlgMask) {
        int mask = 0;
        for (int bit = authAlgMask.nextSetBit(0); bit != -1;
                bit = authAlgMask.nextSetBit(bit + 1)) {
            switch (bit) {
                case WifiConfiguration.AuthAlgorithm.OPEN:
                    mask |= ISupplicantStaNetwork.AuthAlgMask.OPEN;
                    break;
                case WifiConfiguration.AuthAlgorithm.SHARED:
                    mask |= ISupplicantStaNetwork.AuthAlgMask.SHARED;
                    break;
                case WifiConfiguration.AuthAlgorithm.LEAP:
                    mask |= ISupplicantStaNetwork.AuthAlgMask.LEAP;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid authAlgMask bit in wificonfig: " + bit);
            }
        }
        return mask;
    };

    private static int wifiConfigurationToSupplicantGroupCipherMask(BitSet groupCipherMask) {
        int mask = 0;
        for (int bit = groupCipherMask.nextSetBit(0); bit != -1; bit =
                groupCipherMask.nextSetBit(bit + 1)) {
            switch (bit) {
                case WifiConfiguration.GroupCipher.WEP40:
                    mask |= ISupplicantStaNetwork.GroupCipherMask.WEP40;
                    break;
                case WifiConfiguration.GroupCipher.WEP104:
                    mask |= ISupplicantStaNetwork.GroupCipherMask.WEP104;
                    break;
                case WifiConfiguration.GroupCipher.TKIP:
                    mask |= ISupplicantStaNetwork.GroupCipherMask.TKIP;
                    break;
                case WifiConfiguration.GroupCipher.CCMP:
                    mask |= ISupplicantStaNetwork.GroupCipherMask.CCMP;
                    break;
                case WifiConfiguration.GroupCipher.GTK_NOT_USED:
                    mask |= ISupplicantStaNetwork.GroupCipherMask.GTK_NOT_USED;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid GroupCipherMask bit in wificonfig: " + bit);
            }
        }
        return mask;
    };

    private static int wifiConfigurationToSupplicantPairwiseCipherMask(BitSet pairwiseCipherMask) {
        int mask = 0;
        for (int bit = pairwiseCipherMask.nextSetBit(0); bit != -1;
                bit = pairwiseCipherMask.nextSetBit(bit + 1)) {
            switch (bit) {
                case WifiConfiguration.PairwiseCipher.NONE:
                    mask |= ISupplicantStaNetwork.PairwiseCipherMask.NONE;
                    break;
                case WifiConfiguration.PairwiseCipher.TKIP:
                    mask |= ISupplicantStaNetwork.PairwiseCipherMask.TKIP;
                    break;
                case WifiConfiguration.PairwiseCipher.CCMP:
                    mask |= ISupplicantStaNetwork.PairwiseCipherMask.CCMP;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid pairwiseCipherMask bit in wificonfig: " + bit);
            }
        }
        return mask;
    };

    private static int supplicantToWifiConfigurationEapMethod(int value) {
        switch (value) {
            case ISupplicantStaNetwork.EapMethod.PEAP:
                return WifiEnterpriseConfig.Eap.PEAP;
            case ISupplicantStaNetwork.EapMethod.TLS:
                return WifiEnterpriseConfig.Eap.TLS;
            case ISupplicantStaNetwork.EapMethod.TTLS:
                return WifiEnterpriseConfig.Eap.TTLS;
            case ISupplicantStaNetwork.EapMethod.PWD:
                return WifiEnterpriseConfig.Eap.PWD;
            case ISupplicantStaNetwork.EapMethod.SIM:
                return WifiEnterpriseConfig.Eap.SIM;
            case ISupplicantStaNetwork.EapMethod.AKA:
                return WifiEnterpriseConfig.Eap.AKA;
            case ISupplicantStaNetwork.EapMethod.AKA_PRIME:
                return WifiEnterpriseConfig.Eap.AKA_PRIME;
            case ISupplicantStaNetwork.EapMethod.WFA_UNAUTH_TLS:
                return WifiEnterpriseConfig.Eap.UNAUTH_TLS;
            // WifiEnterpriseConfig.Eap.NONE:
            default:
                Log.e(TAG, "invalid eap method value from supplicant: " + value);
                return -1;
        }
    };

    private static int supplicantToWifiConfigurationEapPhase2Method(int value) {
        switch (value) {
            case ISupplicantStaNetwork.EapPhase2Method.NONE:
                return WifiEnterpriseConfig.Phase2.NONE;
            case ISupplicantStaNetwork.EapPhase2Method.PAP:
                return WifiEnterpriseConfig.Phase2.PAP;
            case ISupplicantStaNetwork.EapPhase2Method.MSPAP:
                return WifiEnterpriseConfig.Phase2.MSCHAP;
            case ISupplicantStaNetwork.EapPhase2Method.MSPAPV2:
                return WifiEnterpriseConfig.Phase2.MSCHAPV2;
            case ISupplicantStaNetwork.EapPhase2Method.GTC:
                return WifiEnterpriseConfig.Phase2.GTC;
            default:
                Log.e(TAG, "invalid eap phase2 method value from supplicant: " + value);
                return -1;
        }
    };

    private static int supplicantMaskValueToWifiConfigurationBitSet(int supplicantMask,
                                                             int supplicantValue, BitSet bitset,
                                                             int bitSetPosition) {
        bitset.set(bitSetPosition, (supplicantMask & supplicantValue) == supplicantValue);
        int modifiedSupplicantMask = supplicantMask & ~supplicantValue;
        return modifiedSupplicantMask;
    }

    private static BitSet supplicantToWifiConfigurationKeyMgmtMask(int mask) {
        BitSet bitset = new BitSet();
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.NONE, bitset,
                WifiConfiguration.KeyMgmt.NONE);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.WPA_PSK, bitset,
                WifiConfiguration.KeyMgmt.WPA_PSK);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.WPA_EAP, bitset,
                WifiConfiguration.KeyMgmt.WPA_EAP);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.IEEE8021X, bitset,
                WifiConfiguration.KeyMgmt.IEEE8021X);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.OSEN, bitset,
                WifiConfiguration.KeyMgmt.OSEN);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.FT_PSK, bitset,
                WifiConfiguration.KeyMgmt.FT_PSK);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.KeyMgmtMask.FT_EAP, bitset,
                WifiConfiguration.KeyMgmt.FT_EAP);
        if (mask != 0) {
            throw new IllegalArgumentException(
                    "invalid key mgmt mask from supplicant: " + mask);
        }
        return bitset;
    }

    private static BitSet supplicantToWifiConfigurationProtoMask(int mask) {
        BitSet bitset = new BitSet();
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.ProtoMask.WPA, bitset,
                WifiConfiguration.Protocol.WPA);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.ProtoMask.RSN, bitset,
                WifiConfiguration.Protocol.RSN);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.ProtoMask.OSEN, bitset,
                WifiConfiguration.Protocol.OSEN);
        if (mask != 0) {
            throw new IllegalArgumentException(
                    "invalid proto mask from supplicant: " + mask);
        }
        return bitset;
    };

    private static BitSet supplicantToWifiConfigurationAuthAlgMask(int mask) {
        BitSet bitset = new BitSet();
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.AuthAlgMask.OPEN, bitset,
                WifiConfiguration.AuthAlgorithm.OPEN);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.AuthAlgMask.SHARED, bitset,
                WifiConfiguration.AuthAlgorithm.SHARED);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.AuthAlgMask.LEAP, bitset,
                WifiConfiguration.AuthAlgorithm.LEAP);
        if (mask != 0) {
            throw new IllegalArgumentException(
                    "invalid auth alg mask from supplicant: " + mask);
        }
        return bitset;
    };

    private static BitSet supplicantToWifiConfigurationGroupCipherMask(int mask) {
        BitSet bitset = new BitSet();
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.GroupCipherMask.WEP40, bitset,
                WifiConfiguration.GroupCipher.WEP40);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.GroupCipherMask.WEP104, bitset,
                WifiConfiguration.GroupCipher.WEP104);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.GroupCipherMask.TKIP, bitset,
                WifiConfiguration.GroupCipher.TKIP);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.GroupCipherMask.CCMP, bitset,
                WifiConfiguration.GroupCipher.CCMP);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.GroupCipherMask.GTK_NOT_USED, bitset,
                WifiConfiguration.GroupCipher.GTK_NOT_USED);
        if (mask != 0) {
            throw new IllegalArgumentException(
                    "invalid group cipher mask from supplicant: " + mask);
        }
        return bitset;
    };

    private static BitSet supplicantToWifiConfigurationPairwiseCipherMask(int mask) {
        BitSet bitset = new BitSet();
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.PairwiseCipherMask.NONE, bitset,
                WifiConfiguration.PairwiseCipher.NONE);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.PairwiseCipherMask.TKIP, bitset,
                WifiConfiguration.PairwiseCipher.TKIP);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, ISupplicantStaNetwork.PairwiseCipherMask.CCMP, bitset,
                WifiConfiguration.PairwiseCipher.CCMP);
        if (mask != 0) {
            throw new IllegalArgumentException(
                    "invalid pairwise cipher mask from supplicant: " + mask);
        }
        return bitset;
    };

    private static int wifiConfigurationToSupplicantEapMethod(int value) {
        switch (value) {
            case WifiEnterpriseConfig.Eap.PEAP:
                return ISupplicantStaNetwork.EapMethod.PEAP;
            case WifiEnterpriseConfig.Eap.TLS:
                return ISupplicantStaNetwork.EapMethod.TLS;
            case WifiEnterpriseConfig.Eap.TTLS:
                return ISupplicantStaNetwork.EapMethod.TTLS;
            case WifiEnterpriseConfig.Eap.PWD:
                return ISupplicantStaNetwork.EapMethod.PWD;
            case WifiEnterpriseConfig.Eap.SIM:
                return ISupplicantStaNetwork.EapMethod.SIM;
            case WifiEnterpriseConfig.Eap.AKA:
                return ISupplicantStaNetwork.EapMethod.AKA;
            case WifiEnterpriseConfig.Eap.AKA_PRIME:
                return ISupplicantStaNetwork.EapMethod.AKA_PRIME;
            case WifiEnterpriseConfig.Eap.UNAUTH_TLS:
                return ISupplicantStaNetwork.EapMethod.WFA_UNAUTH_TLS;
            // WifiEnterpriseConfig.Eap.NONE:
            default:
                Log.e(TAG, "invalid eap method value from WifiConfiguration: " + value);
                return -1;
        }
    };

    private static int wifiConfigurationToSupplicantEapPhase2Method(int value) {
        switch (value) {
            case WifiEnterpriseConfig.Phase2.NONE:
                return ISupplicantStaNetwork.EapPhase2Method.NONE;
            case WifiEnterpriseConfig.Phase2.PAP:
                return ISupplicantStaNetwork.EapPhase2Method.PAP;
            case WifiEnterpriseConfig.Phase2.MSCHAP:
                return ISupplicantStaNetwork.EapPhase2Method.MSPAP;
            case WifiEnterpriseConfig.Phase2.MSCHAPV2:
                return ISupplicantStaNetwork.EapPhase2Method.MSPAPV2;
            case WifiEnterpriseConfig.Phase2.GTC:
                return ISupplicantStaNetwork.EapPhase2Method.GTC;
            default:
                Log.e(TAG, "invalid eap phase2 method value from WifiConfiguration: " + value);
                return -1;
        }
    };

    /** See ISupplicantNetwork.hal for documentation */
    private boolean getId() {
        synchronized (mLock) {
            final String methodStr = "getId";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getId((SupplicantStatus status, int idValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mNetworkId = idValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantNetwork.hal for documentation */
    private boolean getInterfaceName() {
        synchronized (mLock) {
            final String methodStr = "getInterfaceName";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getInterfaceName((SupplicantStatus status,
                        String nameValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mIfaceName = nameValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean registerCallback(ISupplicantStaNetworkCallback callback) {
        synchronized (mLock) {
            final String methodStr = "registerCallback";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.registerCallback(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setSsid(java.util.ArrayList<Byte> ssid) {
        synchronized (mLock) {
            final String methodStr = "setSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setSsid(ssid);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setBssid(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            final String methodStr = "setBssid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setBssid(bssid);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setScanSsid(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setScanSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setScanSsid(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setKeyMgmt(int keyMgmtMask) {
        synchronized (mLock) {
            final String methodStr = "setKeyMgmt";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setKeyMgmt(keyMgmtMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setProto(int protoMask) {
        synchronized (mLock) {
            final String methodStr = "setProto";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setProto(protoMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setAuthAlg(int authAlgMask) {
        synchronized (mLock) {
            final String methodStr = "setAuthAlg";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setAuthAlg(authAlgMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setGroupCipher(int groupCipherMask) {
        synchronized (mLock) {
            final String methodStr = "setGroupCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setGroupCipher(groupCipherMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setPairwiseCipher(int pairwiseCipherMask) {
        synchronized (mLock) {
            final String methodStr = "setPairwiseCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.setPairwiseCipher(pairwiseCipherMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setPskPassphrase(String psk) {
        synchronized (mLock) {
            final String methodStr = "setPskPassphrase";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setPskPassphrase(psk);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setWepKey(int keyIdx, java.util.ArrayList<Byte> wepKey) {
        synchronized (mLock) {
            final String methodStr = "setWepKey";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setWepKey(keyIdx, wepKey);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setWepTxKeyIdx(int keyIdx) {
        synchronized (mLock) {
            final String methodStr = "setWepTxKeyIdx";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setWepTxKeyIdx(keyIdx);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setRequirePmf(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setRequirePmf";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setRequirePmf(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setUpdateIdentifier(int identifier) {
        synchronized (mLock) {
            final String methodStr = "setUpdateIdentifier";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setUpdateIdentifier(identifier);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapMethod(int method) {
        synchronized (mLock) {
            final String methodStr = "setEapMethod";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapMethod(method);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapPhase2Method(int method) {
        synchronized (mLock) {
            final String methodStr = "setEapPhase2Method";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapPhase2Method(method);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapIdentity(java.util.ArrayList<Byte> identity) {
        synchronized (mLock) {
            final String methodStr = "setEapIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapIdentity(identity);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapAnonymousIdentity(java.util.ArrayList<Byte> identity) {
        synchronized (mLock) {
            final String methodStr = "setEapAnonymousIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapAnonymousIdentity(identity);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapPassword(java.util.ArrayList<Byte> password) {
        synchronized (mLock) {
            final String methodStr = "setEapPassword";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapPassword(password);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapCACert(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapCACert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapCACert(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapCAPath(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapCAPath";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapCAPath(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapClientCert(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapClientCert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapClientCert(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapPrivateKey(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapPrivateKey";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapPrivateKey(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapSubjectMatch(String match) {
        synchronized (mLock) {
            final String methodStr = "setEapSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapSubjectMatch(match);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapAltSubjectMatch(String match) {
        synchronized (mLock) {
            final String methodStr = "setEapAltSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapAltSubjectMatch(match);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapEngine(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setEapEngine";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapEngine(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapEngineID(String id) {
        synchronized (mLock) {
            final String methodStr = "setEapEngineID";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapEngineID(id);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapDomainSuffixMatch(String match) {
        synchronized (mLock) {
            final String methodStr = "setEapDomainSuffixMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapDomainSuffixMatch(match);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapProactiveKeyCaching(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setEapProactiveKeyCaching";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setProactiveKeyCaching(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setIdStr(String idString) {
        synchronized (mLock) {
            final String methodStr = "setIdStr";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setIdStr(idString);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getSsid() {
        synchronized (mLock) {
            final String methodStr = "getSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getSsid((SupplicantStatus status,
                        java.util.ArrayList<Byte> ssidValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mSsid = ssidValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getBssid() {
        synchronized (mLock) {
            final String methodStr = "getBssid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getBssid((SupplicantStatus status,
                        byte[/* 6 */] bssidValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mBssid = bssidValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getScanSsid() {
        synchronized (mLock) {
            final String methodStr = "getScanSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getScanSsid((SupplicantStatus status,
                        boolean enabledValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mScanSsid = enabledValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getKeyMgmt() {
        synchronized (mLock) {
            final String methodStr = "getKeyMgmt";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getKeyMgmt((SupplicantStatus status,
                        int keyMgmtMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mKeyMgmtMask = keyMgmtMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getProto() {
        synchronized (mLock) {
            final String methodStr = "getProto";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getProto((SupplicantStatus status, int protoMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mProtoMask = protoMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getAuthAlg() {
        synchronized (mLock) {
            final String methodStr = "getAuthAlg";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getAuthAlg((SupplicantStatus status,
                        int authAlgMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mAuthAlgMask = authAlgMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getGroupCipher() {
        synchronized (mLock) {
            final String methodStr = "getGroupCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getGroupCipher((SupplicantStatus status,
                        int groupCipherMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mGroupCipherMask = groupCipherMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getPairwiseCipher() {
        synchronized (mLock) {
            final String methodStr = "getPairwiseCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getPairwiseCipher((SupplicantStatus status,
                        int pairwiseCipherMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mPairwiseCipherMask = pairwiseCipherMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getPskPassphrase() {
        synchronized (mLock) {
            final String methodStr = "getPskPassphrase";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getPskPassphrase((SupplicantStatus status,
                        String pskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mPskPassphrase = pskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getWepKey(int keyIdx) {
        synchronized (mLock) {
            final String methodStr = "keyIdx";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getWepKey(keyIdx, (SupplicantStatus status,
                        java.util.ArrayList<Byte> wepKeyValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mWepKey = wepKeyValue;
                    } else {
                        Log.e(TAG, methodStr + ",  failed: " + status.debugMessage);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getWepTxKeyIdx() {
        synchronized (mLock) {
            final String methodStr = "getWepTxKeyIdx";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getWepTxKeyIdx((SupplicantStatus status,
                        int keyIdxValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mWepTxKeyIdx = keyIdxValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getRequirePmf() {
        synchronized (mLock) {
            final String methodStr = "getRequirePmf";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getRequirePmf((SupplicantStatus status,
                        boolean enabledValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mRequirePmf = enabledValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapMethod() {
        synchronized (mLock) {
            final String methodStr = "getEapMethod";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapMethod((SupplicantStatus status,
                        int methodValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapMethod = methodValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapPhase2Method() {
        synchronized (mLock) {
            final String methodStr = "getEapPhase2Method";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapPhase2Method((SupplicantStatus status,
                        int methodValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapPhase2Method = methodValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapIdentity() {
        synchronized (mLock) {
            final String methodStr = "getEapIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapIdentity((SupplicantStatus status,
                        ArrayList<Byte> identityValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapIdentity = identityValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapAnonymousIdentity() {
        synchronized (mLock) {
            final String methodStr = "getEapAnonymousIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapAnonymousIdentity((SupplicantStatus status,
                        ArrayList<Byte> identityValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapAnonymousIdentity = identityValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapPassword() {
        synchronized (mLock) {
            final String methodStr = "getEapPassword";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapPassword((SupplicantStatus status,
                        ArrayList<Byte> passwordValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapPassword = passwordValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapCACert() {
        synchronized (mLock) {
            final String methodStr = "getEapCACert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapCACert((SupplicantStatus status, String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapCACert = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapCAPath() {
        synchronized (mLock) {
            final String methodStr = "getEapCAPath";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapCAPath((SupplicantStatus status, String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapCAPath = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapClientCert() {
        synchronized (mLock) {
            final String methodStr = "getEapClientCert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapClientCert((SupplicantStatus status,
                        String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapClientCert = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapPrivateKey() {
        synchronized (mLock) {
            final String methodStr = "getEapPrivateKey";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapPrivateKey((SupplicantStatus status,
                        String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapPrivateKey = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapSubjectMatch() {
        synchronized (mLock) {
            final String methodStr = "getEapSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapSubjectMatch((SupplicantStatus status,
                        String matchValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapSubjectMatch = matchValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapAltSubjectMatch() {
        synchronized (mLock) {
            final String methodStr = "getEapAltSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapAltSubjectMatch((SupplicantStatus status,
                        String matchValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapAltSubjectMatch = matchValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapEngine() {
        synchronized (mLock) {
            final String methodStr = "getEapEngine";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapEngine((SupplicantStatus status,
                        boolean enabledValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapEngine = enabledValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapEngineID() {
        synchronized (mLock) {
            final String methodStr = "getEapEngineID";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapEngineID((SupplicantStatus status, String idValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapEngineID = idValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapDomainSuffixMatch() {
        synchronized (mLock) {
            final String methodStr = "getEapDomainSuffixMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapDomainSuffixMatch((SupplicantStatus status,
                        String matchValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapDomainSuffixMatch = matchValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getIdStr() {
        synchronized (mLock) {
            final String methodStr = "getIdStr";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getIdStr((SupplicantStatus status, String idString) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mIdStr = idString;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean enable(boolean noConnect) {
        synchronized (mLock) {
            final String methodStr = "enable";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.enable(noConnect);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean disable() {
        synchronized (mLock) {
            final String methodStr = "disable";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.disable();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Trigger a connection to this network.
     *
     * @return true if it succeeds, false otherwise.
     */
    public boolean select() {
        synchronized (mLock) {
            final String methodStr = "select";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.select();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Send GSM auth response.
     *
     * @param paramsStr Response params as a string.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendNetworkEapSimGsmAuthResponse(String paramsStr) {
        Matcher match = GSM_AUTH_RESPONSE_PARAMS_PATTERN.matcher(paramsStr);
        ArrayList<ISupplicantStaNetwork.NetworkResponseEapSimGsmAuthParams> params =
                new ArrayList<>();
        while (match.find()) {
            if (match.groupCount() != 2) {
                Log.e(TAG, "Malformed gsm auth response params: " + paramsStr);
                return false;
            }
            ISupplicantStaNetwork.NetworkResponseEapSimGsmAuthParams param =
                    new ISupplicantStaNetwork.NetworkResponseEapSimGsmAuthParams();
            byte[] kc = NativeUtil.hexStringToByteArray(match.group(1));
            if (kc == null || kc.length != param.kc.length) {
                Log.e(TAG, "Invalid kc value: " + match.group(1));
                return false;
            }
            byte[] sres = NativeUtil.hexStringToByteArray(match.group(2));
            if (sres == null || sres.length != param.sres.length) {
                Log.e(TAG, "Invalid sres value: " + match.group(2));
                return false;
            }
            System.arraycopy(kc, 0, param.kc, 0, param.kc.length);
            System.arraycopy(sres, 0, param.sres, 0, param.sres.length);
            params.add(param);
        }
        // The number of kc/sres pairs can either be 2 or 3 depending on the request.
        if (params.size() > 3 || params.size() < 2) {
            Log.e(TAG, "Malformed gsm auth response params: " + paramsStr);
            return false;
        }
        return sendNetworkEapSimGsmAuthResponse(params);
    }

    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimGsmAuthResponse(
            ArrayList<ISupplicantStaNetwork.NetworkResponseEapSimGsmAuthParams> params) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimGsmAuthResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapSimGsmAuthResponse(params);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    public boolean sendNetworkEapSimGsmAuthFailure() {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimGsmAuthFailure";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaNetwork.sendNetworkEapSimGsmAuthFailure();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /**
     * Send UMTS auth response.
     *
     * @param paramsStr Response params as a string.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendNetworkEapSimUmtsAuthResponse(String paramsStr) {
        Matcher match = UMTS_AUTH_RESPONSE_PARAMS_PATTERN.matcher(paramsStr);
        if (!match.find() || match.groupCount() != 3) {
            Log.e(TAG, "Malformed umts auth response params: " + paramsStr);
            return false;
        }
        ISupplicantStaNetwork.NetworkResponseEapSimUmtsAuthParams params =
                new ISupplicantStaNetwork.NetworkResponseEapSimUmtsAuthParams();
        byte[] ik = NativeUtil.hexStringToByteArray(match.group(1));
        if (ik == null || ik.length != params.ik.length) {
            Log.e(TAG, "Invalid ik value: " + match.group(1));
            return false;
        }
        byte[] ck = NativeUtil.hexStringToByteArray(match.group(2));
        if (ck == null || ck.length != params.ck.length) {
            Log.e(TAG, "Invalid ck value: " + match.group(2));
            return false;
        }
        byte[] res = NativeUtil.hexStringToByteArray(match.group(3));
        if (res == null || res.length == 0) {
            Log.e(TAG, "Invalid res value: " + match.group(3));
            return false;
        }
        System.arraycopy(ik, 0, params.ik, 0, params.ik.length);
        System.arraycopy(ck, 0, params.ck, 0, params.ck.length);
        for (byte b : res) {
            params.res.add(b);
        }
        return sendNetworkEapSimUmtsAuthResponse(params);
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimUmtsAuthResponse(
            ISupplicantStaNetwork.NetworkResponseEapSimUmtsAuthParams params) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimUmtsAuthResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapSimUmtsAuthResponse(params);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /**
     * Send UMTS auts response.
     *
     * @param paramsStr Response params as a string.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendNetworkEapSimUmtsAutsResponse(String paramsStr) {
        Matcher match = UMTS_AUTS_RESPONSE_PARAMS_PATTERN.matcher(paramsStr);
        if (!match.find() || match.groupCount() != 1) {
            Log.e(TAG, "Malformed umts auts response params: " + paramsStr);
            return false;
        }
        byte[] auts = NativeUtil.hexStringToByteArray(match.group(1));
        if (auts == null || auts.length != 14) {
            Log.e(TAG, "Invalid auts value: " + match.group(1));
            return false;
        }
        return sendNetworkEapSimUmtsAutsResponse(auts);
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimUmtsAutsResponse(byte[/* 14 */] auts) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimUmtsAutsResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapSimUmtsAutsResponse(auts);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    public boolean sendNetworkEapSimUmtsAuthFailure() {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimUmtsAuthFailure";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaNetwork.sendNetworkEapSimUmtsAuthFailure();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /**
     * Send eap identity response.
     *
     * @param identityStr Identity as a string.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendNetworkEapIdentityResponse(String identityStr) {
        ArrayList<Byte> identity = NativeUtil.stringToByteArrayList(identityStr);
        return sendNetworkEapIdentityResponse(identity);
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapIdentityResponse(ArrayList<Byte> identity) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapIdentityResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapIdentityResponse(identity);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(SupplicantStatus status, final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (status.code != SupplicantStatusCode.SUCCESS) {
            Log.e(TAG, methodStr + " failed: "
                    + SupplicantStaIfaceHal.supplicantStatusCodeToString(status.code) + ", "
                    + status.debugMessage);
            return false;
        }
        return true;
    }

    /**
     * Returns false if ISupplicantStaNetwork is null, and logs failure of methodStr
     */
    private boolean checkISupplicantStaNetworkAndLogFailure(final String methodStr) {
        if (mISupplicantStaNetwork == null) {
            Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaNetwork is null");
            return false;
        }
        return true;
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        mISupplicantStaNetwork = null;
        Log.e(TAG, "ISupplicantStaNetwork." + methodStr + ":exception: " + e);
    }

    private void logFailureStatus(SupplicantStatus status, String methodStr) {
        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
    }
}