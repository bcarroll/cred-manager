/*
 * cred-manager is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2017, Gluu
 */
package org.gluu.credmanager.ui.vm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gluu.credmanager.conf.CredentialType;
import org.gluu.credmanager.conf.OTPConfig;
import org.gluu.credmanager.core.WebUtils;
import org.gluu.credmanager.core.credential.OTPDevice;
import org.gluu.credmanager.misc.Utils;
import org.gluu.credmanager.services.OTPService;
import org.zkoss.bind.BindUtils;
import org.zkoss.bind.annotation.*;
import org.zkoss.json.JavaScriptValue;
import org.zkoss.util.Pair;
import org.zkoss.util.resource.Labels;
import org.zkoss.zk.au.out.AuInvoke;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.Selectors;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Messagebox;

import java.util.Date;

/**
 * Created by jgomer on 2017-08-01.
 * This is the ViewModel of page otp-detail.zul. It controls the CRUD of HOTP/TOTP devices
 */
public class UserOTPViewModel extends UserViewModel{

    private static final int QR_SCAN_TIMEOUT=60;
    private Logger logger = LogManager.getLogger(getClass());

    private OTPService otpService;
    private OTPConfig otpConfig;
    private byte secretKey[];
    private String code;
    private OTPDevice newDevice;
    private int editingId;

    private boolean uiPanelOpened;
    private boolean uiQRShown;
    private boolean uiCorrectCode;

    public boolean isUiCorrectCode() {
        return uiCorrectCode;
    }

    public boolean isUiPanelOpened() {
        return uiPanelOpened;
    }

    public boolean isUiQRShown() {
        return uiQRShown;
    }

    public int getEditingId() {
        return editingId;
    }

    public String getCode() {
        return code;
    }

    public OTPDevice getNewDevice() {
        return newDevice;
    }

    public void setNewDevice(OTPDevice newDevice) {
        this.newDevice = newDevice;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public void setUiPanelOpened(boolean uiPanelOpened) {
        this.uiPanelOpened = uiPanelOpened;
    }

    @Init(superclass = true)
    public void childInit(){
        otpService=services.getOtpService();
        otpConfig=services.getAppConfig().getConfigSettings().getOtpConfig();

        devices=user.getCredentials().get(CredentialType.OTP);

        newDevice=new OTPDevice();
        uiPanelOpened=true;
    }

    @Command
    public void showQR(){
        uiQRShown=true;
        uiCorrectCode=false;
        code=null;

        BindUtils.postNotifyChange(null,	null, this, "uiQRShown");
        BindUtils.postNotifyChange(null,	null, this, "uiCorrectCode");

        secretKey=otpService.generateSecretKey();
        String request=otpService.generateSecretKeyUri(secretKey, user.getGivenName());

        JavaScriptValue jvalue=new JavaScriptValue(otpConfig.getFormattedQROptions(WebUtils.getPageWidth()));

        //Calls the startQR javascript function supplying suitable params
        Clients.response(new AuInvoke("startQR", request, otpConfig.getLabel(), jvalue, QR_SCAN_TIMEOUT));
    }

    @AfterCompose
    public void afterCompose(@ContextParam(ContextType.VIEW) Component view){
        Selectors.wireEventListeners(view, this);
    }

    @Listen("onData=#readyButton")
    public void timedOut(Event event) throws Exception {
        if (uiQRShown) {
            //Restore UI because user did not scan code
            uiQRShown = false;
            BindUtils.postNotifyChange(null, null, this, "uiQRShown");
        }
    }

    @NotifyChange("uiCorrectCode")
    @Command
    public void validateCode(@BindingParam("ref") Component ref){

        String uid=null;
        if (code!=null) {
            //Determines if numeric code is valid with respect to QR's secret key
            switch (otpConfig.getType()){
                case HOTP:
                    Pair<Boolean, Long> result = otpService.validateHOTPKey(secretKey, 1, code);
                    if (result.getX())
                        uid = otpService.getExternalHOTPUid(secretKey, result.getY());
                    break;
                case TOTP:
                    if (otpService.validateTOTPKey(secretKey, code))
                        uid = otpService.getExternalTOTPUid(secretKey);
                    break;
            }
            if (uid!=null) {
                uiCorrectCode = true;
                newDevice.setUid(uid);
            }
        }
        if (uid==null) {
            uid = Labels.getLabel("usr.code_wrong");
            Clients.showNotification(uid, Clients.NOTIFICATION_TYPE_WARNING, ref, "before_center", FEEDBACK_DELAY_ERR);
        }

    }

    @Command
    @NotifyChange({"uiQRShown", "uiCorrectCode", "uiPanelOpened", "code", "devices", "newDevice"})
    public void add(){

        if (enroll()){
            showMessageUI(true, Labels.getLabel("usr.enroll.success"));
            uiPanelOpened = false;
            cancel();
        }
        else
            showMessageUI(false, Labels.getLabel("usr.enroll.error"));

    }

    public boolean enroll(){

        boolean success=false;

        //Adds the new device if user typed a nickname in the text box
        if (Utils.stringOptional(newDevice.getNickName()).isPresent())
            try{
                newDevice.setAddedOn(new Date().getTime());
                userService.updateOTPDevicesAdd(user, devices, newDevice);
                success=true;
            }
            catch (Exception e){
                logger.error(e.getMessage(), e);
            }
        return success;

    }

    @NotifyChange({"uiQRShown", "uiCorrectCode", "code", "newDevice"})
    @Command
    public void cancel(){
        Clients.response(new AuInvoke("clean"));
        uiQRShown=false;
        uiCorrectCode=false;
        code=null;
        newDevice=new OTPDevice();
    }

    @NotifyChange({"newDevice","editingId"})
    @Command
    public void prepareForUpdate(@BindingParam("device") OTPDevice dev){
        //This will make the modal window to become visible
        editingId=dev.getId();
        newDevice=new OTPDevice();
        newDevice.setNickName(dev.getNickName());
    }

    @NotifyChange({"editingId", "newDevice"})
    @Command
    public void cancelUpdate(){
        newDevice.setNickName(null);
        editingId=0;
    }

    @NotifyChange({"devices", "editingId", "newDevice"})
    @Command
    public void update(){

        String nick=newDevice.getNickName();
        if (Utils.stringOptional(nick).isPresent()) {
            //Find the index of the current device in the device list
            int i=Utils.firstTrue(devices, OTPDevice.class::cast, dev -> dev.getId()==editingId);
            OTPDevice dev=(OTPDevice) devices.get(i);
            //Updates its nickname
            dev.setNickName(nick);
            cancelUpdate();     //This doesn't undo anything we already did (just controls UI aspects)

            try {
                userService.updateOTPDevicesAdd(user, devices, null);
                showMessageUI(true);
            }
            catch (Exception e){
                showMessageUI(false);
                logger.error(e.getMessage(), e);
            }
        }

    }

    @Command
    public void delete(@BindingParam("device") OTPDevice device){

        boolean flag=mayTriggerResetPreference(CredentialType.OTP, devices.size());
        Pair<String, String> delMessages=getDelMessages(flag, device.getNickName());

        Messagebox.show(delMessages.getY(), delMessages.getX(), Messagebox.YES | Messagebox.NO, flag ? Messagebox.EXCLAMATION : Messagebox.QUESTION,
                event -> {
                        if (Messagebox.ON_YES.equals(event.getName())){
                            try {
                                if (devices.remove(device)) {
                                    if (flag)
                                        userService.setPreferredMethod(user,null);

                                    userService.updateOTPDevicesAdd(user, devices, null);
                                    //trigger refresh (this method is asynchronous...)
                                    BindUtils.postNotifyChange(null, null, UserOTPViewModel.this, "devices");

                                    showMessageUI(true);
                                }
                            }
                            catch (Exception e){
                                showMessageUI(false);
                                logger.error(e.getMessage(), e);
                            }
                        }
                });
    }

}