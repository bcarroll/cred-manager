package org.gluu.credmanager.services.ldap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gluu.credmanager.conf.jsonized.LdapSettings;
import org.gluu.credmanager.core.credential.SecurityKey;
import org.gluu.credmanager.core.credential.VerifiedPhone;
import org.gluu.credmanager.services.ldap.pojo.*;
import org.gluu.site.ldap.LDAPConnectionProvider;
import org.gluu.site.ldap.OperationsFacade;
import org.gluu.site.ldap.persistence.AttributeData;
import org.gluu.site.ldap.persistence.AttributeDataModification;
import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.xdi.util.properties.FileConfiguration;
import org.xdi.util.security.PropertiesDecrypter;
import org.xdi.util.security.StringEncrypter;
import org.zkoss.util.resource.Labels;

import javax.enterprise.context.ApplicationScoped;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by jgomer on 2017-07-06.
 * An app. scoped bean that executes operations on local LDAP. Before usage, setup method has to be called (initializes
 * the connection) - this is done only once during app. start (see AppConfiguration bean).
 * Most operations (methods) in this class are self explanatory
 */
@ApplicationScoped
public class LdapService {

    private static final String OTP_SCRIPT_BRANCH_PATTERN="inum={0}!0011!5018.D4BF,ou=scripts,o={0},o=gluu";
    private Logger logger = LogManager.getLogger(getClass());

    private Properties ldapProperties;
    private LdapEntryManager ldapEntryManager;
    private LdapSettings ldapSettings;
    private ObjectMapper mapper;

    private String strOxTrustConfig =null;
    private String strOxAuthConfig =null;

    /**
     * Initializes and LdapEntryManager instance for operation
     * @param settings LDAP settings as entered into the global configuration file
     * @throws Exception
     */
    public void setup(LdapSettings settings) throws Exception{

        mapper = new ObjectMapper();
        ldapSettings=settings;

        String saltFile=ldapSettings.getSaltLocation();
        ldapProperties = new FileConfiguration(ldapSettings.getOxLdapLocation()).getProperties();
        if (saltFile != null){
            String salt = new FileConfiguration(saltFile).getProperties().getProperty("encodeSalt");
            ldapProperties = PropertiesDecrypter.decryptProperties(StringEncrypter.instance(salt), ldapProperties);
        }
        LDAPConnectionProvider connProvider = new LDAPConnectionProvider(ldapProperties);

        OperationsFacade facade = new OperationsFacade(connProvider);   //bindConnProvider??
        ldapEntryManager= new LdapEntryManager(facade);

        if (ldapEntryManager==null)
            throw new Exception(Labels.getLabel("app.bad_ldapentrymanager"));
    }

    //TODO: refactor these methods to call .find(class,dn) - search in oxauth proper class with the suitable annotations
    public String getOrganizationName() throws Exception{

        String dn=String.format("o=%s,o=gluu", ldapSettings.getOrgInum());
        List<GluuOrganization> list=ldapEntryManager.findEntries(dn, GluuOrganization.class,null);
        return list.get(0).getName();

    }

    private String getStrOxTrustConfig(){

        if (strOxTrustConfig ==null){
            String dn=String.format("ou=oxtrust,ou=configuration,inum=%s,ou=appliances,o=gluu", ldapSettings.getApplianceInum());
            List<OxTrustConfiguration> list=ldapEntryManager.findEntries(dn, OxTrustConfiguration.class,null);
            strOxTrustConfig =list.get(0).getStrConfCacheRefresh();
        }
        return strOxTrustConfig;
    }

    private String getStrOxAuthConfig(){

        if (strOxAuthConfig ==null){
            String dn=String.format("ou=oxauth,ou=configuration,inum=%s,ou=appliances,o=gluu", ldapSettings.getApplianceInum());
            List<OxAuthConfiguration> list=ldapEntryManager.findEntries(dn, OxAuthConfiguration.class,null);
            strOxAuthConfig =list.get(0).getStrConfDynamic();
        }
        return strOxAuthConfig;
    }

    public GluuPerson getGluuPerson(String rdn) throws Exception{
        String dn=String.format("%s,ou=people,o=%s,o=gluu", rdn, ldapSettings.getOrgInum());
        return ldapEntryManager.find(GluuPerson.class, dn);
    }

    /**
     * Tries to determine whether local installation of Gluu is using a backend LDAP. This reads the OxTrust configuration
     * Json and inspects inside property "sourceConfigs"
     * @return
     * @throws Exception
     */
    public boolean isBackendLdapEnabled() throws Exception{

        JsonNode tree=mapper.readTree(getStrOxTrustConfig());

        List<Boolean> enabledList=new ArrayList<>();
        tree.get("sourceConfigs").forEach(node -> enabledList.add(node.get("enabled").asBoolean()));
        return enabledList.stream().anyMatch(item -> item);

    }

    public String getOIDCEndpoint() throws Exception{
        JsonNode tree=mapper.readTree(getStrOxAuthConfig());
        return tree.get("openIdConfigurationEndpoint").asText();
    }

    public String getIssuerUrl() throws Exception{
        JsonNode tree=mapper.readTree(getStrOxAuthConfig());
        return tree.get("issuer").asText();
    }

    public List<SecurityKey> getFidoDevices(String userRdn){
        String dn=String.format("%s,ou=people,o=%s,o=gluu", userRdn, ldapSettings.getOrgInum());
        return ldapEntryManager.findEntries(String.format("ou=fido,%s", dn), SecurityKey.class, null);
    }

    /**
     * Updates in LDAP the attributes that store the raw mobile phones as well as the Json representation that contains
     * the credential information associated to those phones for the person being referenced
     * @param userRdn LDAP RDN of user
     * @param mobs List of current VerifiedPhones (only the number attribute is read by this method)
     * @param phone An additional phone to the list
     * @param jsonPhones A Json representation of an array of VerifiedPhones. The information related to parameter phone
     *                   is already included here
     * @throws Exception
     */
    public void updateMobilePhones(String userRdn, List<VerifiedPhone> mobs, VerifiedPhone phone, String jsonPhones) throws Exception{
    /*
        String attributeName="mobile";
        String dn=String.format("%s,ou=people,o=%s,o=gluu", userRdn, ldapSettings.getOrgInum());

        //Extract list of numbers first
        List<String> phones=mobs.stream().map(VerifiedPhone::getNumber).collect(Collectors.toList());
        String[] emptyArr =new String[]{};

        //Delete previous phones
        AttributeData data=new AttributeData(attributeName, phones.toArray(emptyArr));
        AttributeDataModification mod=new AttributeDataModification(AttributeDataModification.AttributeModificationType.REMOVE, null, data);
        ldapEntryManager.merge(dn, Collections.singletonList(mod));

        //Create new phones
        List<String> newPhones=new ArrayList<> (phones);
        newPhones.add(phone.getNumber());
        data=new AttributeData(attributeName, newPhones.toArray(emptyArr));
        mod=new AttributeDataModification(AttributeDataModification.AttributeModificationType.ADD, data, null);
        ldapEntryManager.merge(dn, Collections.singletonList(mod));
    */
        GluuPerson person=getGluuPerson(userRdn);

        List<String> phones=mobs.stream().map(VerifiedPhone::getNumber).collect(Collectors.toList());
        phones.add(phone.getNumber());
        person.setMobileNumbers(phones);

        person.setVerifiedPhonesJson(jsonPhones);
        ldapEntryManager.merge(person);

    }

    public CustomScript getOTPScriptInfo() throws Exception{
        String dn= MessageFormat.format(OTP_SCRIPT_BRANCH_PATTERN, ldapSettings.getOrgInum());
        return ldapEntryManager.find(CustomScript.class, dn);
    }

}
