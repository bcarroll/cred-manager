/*
 * cred-manager is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2017, Gluu
 */
package org.gluu.credmanager.conf;

/**
 * Created by jgomer on 2017-07-24.
 * An enumeration that holds the response codes that a u2f device registration request may output
 */
public enum U2fClientCodes {
    OTHER_ERROR, BAD_REQUEST, CONFIGURATION_UNSUPPORTED, DEVICE_INELIGIBLE, TIMEOUT;

    public static U2fClientCodes get(int ord){
        return U2fClientCodes.values()[ord-1];
    }

}
