/*
 * Microsoft JDBC Driver for SQL Server
 * 
 * Copyright(c) Microsoft Corporation All rights reserved.
 * 
 * This program is made available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */

package com.microsoft.sqlserver.jdbc;

import java.lang.reflect.Method;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.NamingException;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import com.microsoft.sqlserver.jdbc.dns.DNSKerberosLocator;

/**
 * KerbAuthentication for int auth.
 */
final class KerbAuthentication extends SSPIAuthentication {
    private final static String CONFIGNAME = "SQLJDBCDriver";
    private final static java.util.logging.Logger authLogger = java.util.logging.Logger
            .getLogger("com.microsoft.sqlserver.jdbc.internals.KerbAuthentication");

    private final SQLServerConnection con;
    private final String spn;

    private final GSSManager manager = GSSManager.getInstance();
    private LoginContext lc = null;
    private GSSCredential peerCredentials = null;
    private GSSContext peerContext = null;

    static {
        // The driver on load will look to see if there is a configuration set for the SQLJDBCDriver, if not it will install its
        // own configuration. Note it is possible that there is a configuration exists but it does not contain a configuration entry
        // for the driver in that case, we will override the configuration but will flow the configuration requests to existing
        // config for anything other than SQLJDBCDriver
        //
        class SQLJDBCDriverConfig extends Configuration {
            Configuration current = null;
            AppConfigurationEntry[] driverConf;

            SQLJDBCDriverConfig() {
                try {
                    current = Configuration.getConfiguration();
                }
                catch (SecurityException e) {
                    // if we cant get the configuration, it is likely that no configuration has been specified. So go ahead and set the config
                    authLogger.finer(toString() + " No configurations provided, setting driver default");
                }
                AppConfigurationEntry[] config = null;

                if (null != current) {
                    config = current.getAppConfigurationEntry(CONFIGNAME);
                }
                // If there is user provided configuration we leave use that and not install our configuration
                if (null == config) {
                    if (authLogger.isLoggable(Level.FINER))
                        authLogger.finer(toString() + " SQLJDBCDriver configuration entry is not provided, setting driver default");

                    AppConfigurationEntry appConf;
                    if (Util.isIBM()) {
                        Map<String, String> confDetails = new HashMap<String, String>();
                        confDetails.put("useDefaultCcache", "true");
                        confDetails.put("moduleBanner", "false");
                        appConf = new AppConfigurationEntry("com.ibm.security.auth.module.Krb5LoginModule",
                                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, confDetails);
                        if (authLogger.isLoggable(Level.FINER))
                            authLogger.finer(toString() + " Setting IBM Krb5LoginModule");
                    }
                    else {
                        Map<String, String> confDetails = new HashMap<String, String>();
                        confDetails.put("useTicketCache", "true");
                        confDetails.put("doNotPrompt", "true");
                        appConf = new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, confDetails);
                        if (authLogger.isLoggable(Level.FINER))
                            authLogger.finer(toString() + " Setting Sun Krb5LoginModule");
                    }
                    driverConf = new AppConfigurationEntry[1];
                    driverConf[0] = appConf;
                    Configuration.setConfiguration(this);
                }

            }

            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                // we should only handle anything that is related to our part, everything else is handled by the configuration
                // already existing configuration if there is one.
                if (name.equals(CONFIGNAME)) {
                    return driverConf;
                }
                else {
                    if (null != current)
                        return current.getAppConfigurationEntry(name);
                    else
                        return null;
                }
            }

            public void refresh() {
                if (null != current)
                    current.refresh();
            }
        }
        SQLJDBCDriverConfig driverconfig = new SQLJDBCDriverConfig();
    }

    private void intAuthInit() throws SQLServerException {
        try {
            // If we need to support NTLM as well, we can use null
            // Kerberos OID
            Oid kerberos = new Oid("1.2.840.113554.1.2.2");
            Subject currentSubject = null;
            try {
                AccessControlContext context = AccessController.getContext();
                currentSubject = Subject.getSubject(context);
                if (null == currentSubject) {
                    lc = new LoginContext(CONFIGNAME);
                    lc.login();
                    // per documentation LoginContext will instantiate a new subject.
                    currentSubject = lc.getSubject();
                }
            }
            catch (LoginException le) {
                con.terminate(SQLServerException.DRIVER_ERROR_NONE, SQLServerException.getErrString("R_integratedAuthenticationFailed"), le);
            }

            // http://blogs.sun.com/harcey/entry/of_java_kerberos_and_access
            // We pass null to indicate that the system should interpret the SPN as it is.
            GSSName remotePeerName = manager.createName(spn, null);
            if (authLogger.isLoggable(Level.FINER)) {
                authLogger.finer(toString() + " Getting client credentials");
            }
            peerCredentials = getClientCredential(currentSubject, manager, kerberos);
            if (authLogger.isLoggable(Level.FINER)) {
                authLogger.finer(toString() + " creating security context");
            }

            peerContext = manager.createContext(remotePeerName, kerberos, peerCredentials, GSSContext.DEFAULT_LIFETIME);
            // The following flags should be inline with our native implementation.
            peerContext.requestCredDeleg(true);
            peerContext.requestMutualAuth(true);
            peerContext.requestInteg(true);
        }

        catch (GSSException ge) {
            authLogger.finer(toString() + "initAuthInit failed GSSException:-" + ge);
            con.terminate(SQLServerException.DRIVER_ERROR_NONE, SQLServerException.getErrString("R_integratedAuthenticationFailed"), ge);
        }
        catch (PrivilegedActionException ge) {
            authLogger.finer(toString() + "initAuthInit failed privileged exception:-" + ge);
            con.terminate(SQLServerException.DRIVER_ERROR_NONE, SQLServerException.getErrString("R_integratedAuthenticationFailed"), ge);
        }

    }

    // We have to do a privileged action to create the credential of the user in the current context
    private static GSSCredential getClientCredential(final Subject subject,
            final GSSManager MANAGER,
            final Oid kerboid) throws PrivilegedActionException {
        final PrivilegedExceptionAction<GSSCredential> action = new PrivilegedExceptionAction<GSSCredential>() {
            public GSSCredential run() throws GSSException {
                return MANAGER.createCredential(null // use the default principal
                , GSSCredential.DEFAULT_LIFETIME, kerboid, GSSCredential.INITIATE_ONLY);
            }
        };
        // TO support java 5, 6 we have to do this
        // The signature for Java 5 returns an object 6 returns GSSCredential, immediate casting throws
        // warning in Java 6.
        Object credential = Subject.doAs(subject, action);
        return (GSSCredential) credential;
    }

    private byte[] intAuthHandShake(byte[] pin,
            boolean[] done) throws SQLServerException {
        try {
            if (authLogger.isLoggable(Level.FINER)) {
                authLogger.finer(toString() + " Sending token to server over secure context");
            }
            byte[] byteToken = peerContext.initSecContext(pin, 0, pin.length);

            if (peerContext.isEstablished()) {
                done[0] = true;
                if (authLogger.isLoggable(Level.FINER))
                    authLogger.finer(toString() + "Authentication done.");
            }
            else if (null == byteToken) {
                // The documentation is not clear on when this can happen but it does say this could happen
                authLogger.info(toString() + "byteToken is null in initSecContext.");
                con.terminate(SQLServerException.DRIVER_ERROR_NONE, SQLServerException.getErrString("R_integratedAuthenticationFailed"));
            }
            return byteToken;
        }
        catch (GSSException ge) {
            authLogger.finer(toString() + "initSecContext Failed :-" + ge);
            con.terminate(SQLServerException.DRIVER_ERROR_NONE, SQLServerException.getErrString("R_integratedAuthenticationFailed"), ge);
        }
        // keep the compiler happy
        return null;
    }

    private String makeSpn(String server,
            int port) throws SQLServerException {
        if (authLogger.isLoggable(Level.FINER)) {
            authLogger.finer(toString() + " Server: " + server + " port: " + port);
        }
        StringBuilder spn = new StringBuilder("MSSQLSvc/");
        // Format is MSSQLSvc/myhost.domain.company.com:1433
        // FQDN must be provided
        if (con.serverNameAsACE()) {
            spn.append(IDN.toASCII(server));
        }
        else {
            spn.append(server);
        }
        spn.append(":");
        spn.append(port);
        String strSPN = spn.toString();
        if (authLogger.isLoggable(Level.FINER)) {
            authLogger.finer(toString() + " SPN: " + strSPN);
        }
        return strSPN;
    }

    // Package visible members below.
    KerbAuthentication(SQLServerConnection con,
            String address,
            int port) throws SQLServerException {
        this.con = con;
        // Get user provided SPN string; if not provided then build the generic one
        String userSuppliedServerSpn = con.activeConnectionProperties.getProperty(SQLServerDriverStringProperty.SERVER_SPN.toString());

        String spn;
        if (null != userSuppliedServerSpn) {
            // serverNameAsACE is true, translate the user supplied serverSPN to ASCII
            if (con.serverNameAsACE()) {
                int slashPos = userSuppliedServerSpn.indexOf("/");
                spn = userSuppliedServerSpn.substring(0, slashPos + 1) + IDN.toASCII(userSuppliedServerSpn.substring(slashPos + 1));
            }
            else {
                spn = userSuppliedServerSpn;
            }
        }
        else {
            spn = makeSpn(address, port);
        }
        this.spn = enrichSpnWithRealm(spn, null == userSuppliedServerSpn);
        if (!this.spn.equals(spn) && authLogger.isLoggable(Level.FINER)){
            authLogger.finer(toString() + "SPN enriched: " + spn + " := " + this.spn);
        }
	}

    private static final Pattern SPN_PATTERN = Pattern.compile("MSSQLSvc/(.*):([^:@]+)(@.+)?", Pattern.CASE_INSENSITIVE);

    private String enrichSpnWithRealm(String spn,
            boolean allowHostnameCanonicalization) {
        if (spn == null) {
            return spn;
        }
        Matcher m = SPN_PATTERN.matcher(spn);
        if (!m.matches()) {
            return spn;
        }
        if (m.group(3) != null) {
            // Realm is already present, no need to enrich, the job has already been done
            return spn;
        }
        String dnsName = m.group(1);
        String portOrInstance = m.group(2);
        RealmValidator realmValidator = getRealmValidator(dnsName);
        String realm = findRealmFromHostname(realmValidator, dnsName);
        if (realm == null && allowHostnameCanonicalization) {
            // We failed, try with canonical host name to find a better match
            try {
                String canonicalHostName = InetAddress.getByName(dnsName).getCanonicalHostName();
                realm = findRealmFromHostname(realmValidator, canonicalHostName);
                // Since we have a match, our hostname is the correct one (for instance of server
                // name was an IP), so we override dnsName as well
                dnsName = canonicalHostName;
            }
            catch (UnknownHostException cannotCanonicalize) {
                // ignored, but we are in a bad shape
            }
        }
        if (realm == null) {
            return spn;
        }
        else {
            StringBuilder sb = new StringBuilder("MSSQLSvc/");
            sb.append(dnsName).append(":").append(portOrInstance).append("@").append(realm.toUpperCase(Locale.ENGLISH));
            return sb.toString();
        }
    }

    private static RealmValidator validator;

    /**
     * Find a suitable way of validating a REALM for given JVM.
     *
     * @param hostnameToTest
     *            an example hostname we are gonna use to test our realm validator.
     * @return a not null realm Validator.
     */
    static RealmValidator getRealmValidator(String hostnameToTest) {
        if (validator != null) {
            return validator;
        }
        // JVM Specific, here Sun/Oracle JVM
        try {
            Class<?> clz = Class.forName("sun.security.krb5.Config");
            Method getInstance = clz.getMethod("getInstance", new Class[0]);
            final Method getKDCList = clz.getMethod("getKDCList", new Class[] {String.class});
            final Object instance = getInstance.invoke(null);
            RealmValidator oracleRealmValidator = new RealmValidator() {

                @Override
                public boolean isRealmValid(String realm) {
                    try {
                        Object ret = getKDCList.invoke(instance, realm);
                        return ret != null;
                    }
                    catch (Exception err) {
                        return false;
                    }
                }
            };
            validator = oracleRealmValidator;
            // As explained here: https://github.com/Microsoft/mssql-jdbc/pull/40#issuecomment-281509304
            // The default Oracle Resolution mechanism is not bulletproof
            // If it resolves a crappy name, drop it.
            if (!validator.isRealmValid("this.might.not.exist." + hostnameToTest)) {
                // Our realm validator is well working, return it
                authLogger.fine("Kerberos Realm Validator: Using Built-in Oracle Realm Validation method.");
                return oracleRealmValidator;
            }
            authLogger.fine("Kerberos Realm Validator: Detected buggy Oracle Realm Validator, using DNSKerberosLocator.");
        }
        catch (ReflectiveOperationException notTheRightJVMException) {
            // Ignored, we simply are not using the right JVM
            authLogger.fine("Kerberos Realm Validator: No Oracle Realm Validator Available, using DNSKerberosLocator.");
        }
        // No implementation found, default one, not any realm is valid
        validator = new RealmValidator() {
            @Override
            public boolean isRealmValid(String realm) {
                try {
                    return DNSKerberosLocator.isRealmValid(realm);
                }
                catch (NamingException err) {
                    return false;
                }
            }
        };
        return validator;
    }

    /**
     * Try to find a REALM in the different parts of a host name.
     *
     * @param realmValidator
     *            a function that return true if REALM is valid and exists
     * @param hostname
     *            the name we are looking a REALM for
     * @return the realm if found, null otherwise
     */
    private String findRealmFromHostname(RealmValidator realmValidator,
            String hostname) {
        if (hostname == null) {
            return null;
        }
        int index = 0;
        while (index != -1 && index < hostname.length() - 2) {
            String realm = hostname.substring(index);
            if (authLogger.isLoggable(Level.FINEST)) {
                authLogger.finest(toString() + " looking up REALM candidate " + realm);
            }
            if (realmValidator.isRealmValid(realm)) {
                return realm.toUpperCase();
            }
            index = hostname.indexOf(".", index + 1);
            if (index != -1) {
                index = index + 1;
            }
        }
        return null;
    }

    /**
     * JVM Specific implementation to decide whether a realm is valid or not
     */
    interface RealmValidator {
        boolean isRealmValid(String realm);
    }

    byte[] GenerateClientContext(byte[] pin,
            boolean[] done) throws SQLServerException {
        if (null == peerContext) {
            intAuthInit();
        }
        return intAuthHandShake(pin, done);
    }

    int ReleaseClientContext() throws SQLServerException {
        try {
            if (null != peerCredentials)
                peerCredentials.dispose();
            if (null != peerContext)
                peerContext.dispose();
            if (null != lc)
                lc.logout();
        }
        catch (LoginException e) {
            // yes we are eating exceptions here but this should not fail in the normal circumstances and we do not want to eat previous
            // login errors if caused before which is more useful to the user than the cleanup errors.
            authLogger.fine(toString() + " Release of the credentials failed LoginException: " + e);
        }
        catch (GSSException e) {
            // yes we are eating exceptions here but this should not fail in the normal circumstances and we do not want to eat previous
            // login errors if caused before which is more useful to the user than the cleanup errors.
            authLogger.fine(toString() + " Release of the credentials failed GSSException: " + e);
        }
        return 0;
    }
}
