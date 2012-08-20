/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/

package org.ejbca.core.ejb.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.SessionContext;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.cesecore.authentication.tokens.AlwaysAllowLocalAuthenticationToken;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authentication.tokens.UsernamePrincipal;
import org.cesecore.authentication.tokens.X509CertificateAuthenticationToken;
import org.cesecore.authorization.AuthorizationDeniedException;
import org.cesecore.authorization.cache.AccessTreeUpdateSessionLocal;
import org.cesecore.authorization.control.AccessControlSessionLocal;
import org.cesecore.authorization.rules.AccessRuleData;
import org.cesecore.authorization.rules.AccessRuleNotFoundException;
import org.cesecore.authorization.rules.AccessRuleState;
import org.cesecore.authorization.user.AccessUserAspectData;
import org.cesecore.certificates.ca.CA;
import org.cesecore.certificates.ca.CADoesntExistsException;
import org.cesecore.certificates.ca.CAInfo;
import org.cesecore.certificates.ca.CaSessionLocal;
import org.cesecore.certificates.ca.X509CA;
import org.cesecore.certificates.ca.extendedservices.ExtendedCAServiceInfo;
import org.cesecore.certificates.certificateprofile.CertificatePolicy;
import org.cesecore.certificates.certificateprofile.CertificateProfile;
import org.cesecore.certificates.certificateprofile.CertificateProfileData;
import org.cesecore.certificates.certificateprofile.CertificateProfileSessionLocal;
import org.cesecore.jndi.JndiConstants;
import org.cesecore.keys.token.IllegalCryptoTokenException;
import org.cesecore.roles.RoleData;
import org.cesecore.roles.RoleNotFoundException;
import org.cesecore.roles.access.RoleAccessSessionLocal;
import org.cesecore.roles.management.RoleManagementSessionLocal;
import org.cesecore.util.JBossUnmarshaller;
import org.ejbca.core.ejb.authorization.ComplexAccessControlSessionLocal;
import org.ejbca.core.ejb.hardtoken.HardTokenData;
import org.ejbca.core.ejb.hardtoken.HardTokenIssuerData;
import org.ejbca.core.ejb.ra.raadmin.AdminPreferencesData;
import org.ejbca.core.ejb.ra.raadmin.EndEntityProfileData;
import org.ejbca.core.ejb.ra.raadmin.GlobalConfigurationData;
import org.ejbca.core.model.authorization.AccessRulesConstants;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.CmsCAService;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.ExtendedCAServiceTypes;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.HardTokenEncryptCAService;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.HardTokenEncryptCAServiceInfo;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.KeyRecoveryCAService;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.KeyRecoveryCAServiceInfo;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.OCSPCAService;
import org.ejbca.core.model.ca.caadmin.extendedcaservices.XKMSCAService;
import org.ejbca.util.JDBCUtil;
import org.ejbca.util.SqlExecutor;

/**
 * The upgrade session bean is used to upgrade the database between EJBCA
 * releases.
 * 
 * @version $Id$
 */
@Stateless(mappedName = JndiConstants.APP_JNDI_PREFIX + "UpgradeSessionRemote")
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class UpgradeSessionBean implements UpgradeSessionLocal, UpgradeSessionRemote {

    private static final Logger log = Logger.getLogger(UpgradeSessionBean.class);

    @PersistenceContext(unitName = "ejbca")
    private EntityManager entityManager;

    @Resource
    private SessionContext sessionContext;

    @EJB
    private AccessControlSessionLocal accessControlSession;
    @EJB
    private AccessTreeUpdateSessionLocal accessTreeUpdateSession;
    @EJB
    private CaSessionLocal caSession;
    @EJB
    private ComplexAccessControlSessionLocal complexAccessControlSession;
    @EJB
    private RoleAccessSessionLocal roleAccessSession;
    @EJB
    private RoleManagementSessionLocal roleMgmtSession;
    @EJB
    private CertificateProfileSessionLocal certProfileSession;
   

    private UpgradeSessionLocal upgradeSession;

    @PostConstruct
    public void ejbCreate() {
    	upgradeSession = sessionContext.getBusinessObject(UpgradeSessionLocal.class);
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    @Override
    public boolean upgrade( String dbtype, String sOldVersion, boolean isPost) {

        try {
            log.debug("Upgrading from version=" + sOldVersion);
            final int oldVersion;
            {
                final String[] oldVersionArray = sOldVersion.split("\\."); // Split around the '.'-char
                oldVersion = Integer.parseInt(oldVersionArray[0]) * 100 + Integer.parseInt(oldVersionArray[1]);
            }
            if (isPost) {
                return postUpgrade(oldVersion, dbtype);
            }
            return upgrade(dbtype, oldVersion);
        } finally {
            log.trace("<upgrade()");
        }
    }

    private boolean postUpgrade(int oldVersion, String dbtype) {
    	log.debug(">post-upgrade from version: "+oldVersion);
        if (oldVersion < 311) {
            log.error("Only upgrade from EJBCA 3.11.x is supported in EJBCA 4.0.x.");
            return false;
        }
        // Upgrade database change between EJBCA 3.11.x and EJBCA 4.0.x if needed
        if (oldVersion < 400) {
        	if (!postMigrateDatabase400()) {
        		return false;
        	}
        }
        // Upgrade database change between EJBCA 4.0.x and EJBCA 5.0.x if needed, and previous post-upgrade succeeded
        if ((oldVersion < 500)) {
        	if (!postMigrateDatabase500(dbtype)) {
        		return false;
        	}
        }
        return true;
    }

    private boolean upgrade(String dbtype, int oldVersion) {
    	log.debug(">upgrade from version: "+oldVersion+", with dbtype: "+dbtype);
        if (oldVersion < 311) {
            log.error("Only upgrade from EJBCA 3.11.x is supported in EJBCA 4.0.x and higher.");
            return false;
        }
        // Upgrade between EJBCA 3.11.x and EJBCA 4.0.x to 5.0.x
        if (oldVersion <= 500) {
        	if (!migrateDatabase500(dbtype)) {
        		return false;
        	}
        }

        return true;
    }

    /**
     * Called from other migrate methods, don't call this directly, call from an
     * interface-method
     * 
     */
    private boolean migrateDatabase(String resource) {
        // Fetch the resource file with SQL to modify the database tables
        InputStream in = this.getClass().getResourceAsStream(resource);
        if (in == null) {
            log.error("Can not read resource for database '" + resource + "', this database probably does not need table definition changes.");
            // no error
            return true;
        }
        // Migrate database tables to new columns etc
        Connection con = null;
        log.info("Start migration of database.");
        try {
            InputStreamReader inreader = new InputStreamReader(in);
            con = JDBCUtil.getDBConnection();
            SqlExecutor sqlex = new SqlExecutor(con, false);
            sqlex.runCommands(inreader);
        } catch (SQLException e) {
            log.error("SQL error during database migration: ", e);
            return false;
        } catch (IOException e) {
            log.error("IO error during database migration: ", e);
            return false;
        } finally {
            JDBCUtil.close(con);
        }
        log.info("Finished migration of database.");
        return true;
    }

    /**
     * (ECA-200:) In EJB 2.1 JBoss CMP used it's own serialization method for all Object/BLOB fields.
     * 
     * This affects the following entity fields:
     * - CertificateProfileData.data
     * - HardTokenData.data
     * - HardTokenIssuerData.data
     * - LogConfigurationData.logConfiguration
     * - AdminPreferencesData.data
     * - EndEntityProfileData.data
     * - GlobalConfigurationData.data
     * 
     * NOTE: You only need to run this if you upgrade a JBoss installation.
     */
    private boolean postMigrateDatabase400() {
    	log.error("(this is not an error) Starting post upgrade from ejbca 3.11.x to ejbca 4.0.x");
    	boolean ret = true;
    	upgradeSession.postMigrateDatabase400SmallTables();	// Migrate small tables in a new transaction 
    	log.info(" Processing HardTokenData entities.");
    	log.info(" - Building a list of entities.");
    	final List<String> tokenSNs = HardTokenData.findAllTokenSN(entityManager);
    	int position = 0;
    	final int chunkSize = 1000;
    	while (position < tokenSNs.size()) {
        	log.info(" - Processing entity " + position + " to " + Math.min(position+chunkSize-1, tokenSNs.size()-1) + ".");
        	// Migrate HardTokenData table in chunks, each running in a new transaction
    		upgradeSession.postMigrateDatabase400HardTokenData(getSubSet(tokenSNs, position, chunkSize));
    		position += chunkSize;
    	}
    	log.error("(this is not an error) Finished post upgrade from ejbca 3.11.x to ejbca 4.0.x with result: "+ret);
        return ret;
    }
    
    /** @return a subset of the source list with index as its first item and index+count-1 as its last. */
    private <T> List<T> getSubSet(final List<T> source, final int index, final int count) {
    	List<T> ret = new ArrayList<T>(count);
    	for (int i=0; i<count; i++) {
    		ret.add(source.get(index + i));
    	}
    	return ret;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void postMigrateDatabase400SmallTables() {
    	// LogConfiguration removed for EJBCA 5.0, so no upgrade of that needed
    	log.info(" Processing CertificateProfileData entities.");
    	final List<CertificateProfileData> cpds = CertificateProfileData.findAll(entityManager);
    	for (CertificateProfileData cpd : cpds) {
    		// When the wrong class is given it can either return null, or throw an exception
    		HashMap h = getDataUnsafe(cpd.getDataUnsafe());
    		cpd.setDataUnsafe(h);
    	}
    	log.info(" Processing HardTokenIssuerData entities.");
    	final List<HardTokenIssuerData> htids = HardTokenIssuerData.findAll(entityManager);
    	for (HardTokenIssuerData htid : htids) {
    		HashMap h = getDataUnsafe(htid.getDataUnsafe());
    		htid.setDataUnsafe(h);
    	}
    	log.info(" Processing AdminPreferencesData entities.");
    	final List<AdminPreferencesData> apds = AdminPreferencesData.findAll(entityManager);
    	for (AdminPreferencesData apd : apds) {
    		HashMap h = getDataUnsafe(apd.getDataUnsafe());
    		apd.setDataUnsafe(h);
    	}
    	log.info(" Processing EndEntityProfileData entities.");
    	final List<EndEntityProfileData> eepds = EndEntityProfileData.findAll(entityManager);
    	for (EndEntityProfileData eepd : eepds) {
    		HashMap h = getDataUnsafe(eepd.getDataUnsafe());
    		eepd.setDataUnsafe(h);
    	}
    	log.info(" Processing GlobalConfigurationData entities.");
    	GlobalConfigurationData gcd = GlobalConfigurationData.findByConfigurationId(entityManager, "0");
		HashMap h = getDataUnsafe(gcd.getDataUnsafe());
    	gcd.setDataUnsafe(h);
    }

	/**
	 * @param cpd
	 * @return
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
    private HashMap getDataUnsafe(Serializable s) {
		HashMap h = null; 
		try {
			h = JBossUnmarshaller.extractObject(LinkedHashMap.class, s);
			if (h == null) {
				h = new LinkedHashMap(JBossUnmarshaller.extractObject(HashMap.class, s));
			}
		} catch (ClassCastException e) {
			h = new LinkedHashMap(JBossUnmarshaller.extractObject(HashMap.class, s));
		}
		return h;
	}
    
    @SuppressWarnings("rawtypes")
    @Override
    public void postMigrateDatabase400HardTokenData(List<String> subSet) {
    	for (String tokenSN : subSet) {
    		HardTokenData htd = HardTokenData.findByTokenSN(entityManager, tokenSN);
    		if (htd != null) {
        		HashMap h = getDataUnsafe(htd);
        		htd.setDataUnsafe(h);
    		} else {
    	    	log.warn("Hard token was removed during processing. Ignoring token with serial number '" + tokenSN + "'.");
    		}
    	}
    }

    /**
     * In EJBCA 5.0 we have introduced a new authorization rule system.
     * The old "/super_administrator" rule is replaced by a rule to access "/" (AccessRulesConstants.ROLE_ROOT) with recursive=true.
     * therefore we must insert a new access rule in the database in all roles that have super_administrator access.
     * 
     * We have also added a column to the table AdminEntityData: tokenType
     * 
     * @param dbtype A string representation of the actual database.
     * 
     * @throws AuthorizationDeniedException 
     * @throws RoleNotFoundException 
     * @throws AccessRuleNotFoundException 
     * 
     */
    @SuppressWarnings("unchecked")
    private boolean migrateDatabase500(String dbtype) {
    	log.error("(this is not an error) Starting upgrade from ejbca 4.0.x to ejbca 5.0.x");
    	boolean ret = true;
    	
    	AuthenticationToken admin = new AlwaysAllowLocalAuthenticationToken(new UsernamePrincipal("UpgradeSessionBean.migrateDatabase500"));

    	//Upgrade database
    	migrateDatabase("/400_500/400_500-upgrade-"+dbtype+".sql");
    	
    	// fix CAs that don't have classpath for extended CA services
    	Collection<Integer> caids = caSession.getAvailableCAs();
    	for (Integer caid : caids) {
    		try {
				CA ca = caSession.getCAForEdit(admin, caid);
				if (ca.getCAType() == CAInfo.CATYPE_X509) {
					Collection<Integer> extendedServiceTypes = ca.getExternalCAServiceTypes();
					for (Integer type : extendedServiceTypes) {
						ExtendedCAServiceInfo info = ca.getExtendedCAServiceInfo(type);
						if (info == null) {
							@SuppressWarnings("rawtypes")
                            HashMap data = ca.getExtendedCAServiceData(type);
							switch (type) {
							case ExtendedCAServiceTypes.TYPE_OCSPEXTENDEDSERVICE:
								data.put(ExtendedCAServiceInfo.IMPLEMENTATIONCLASS, OCSPCAService.class.getName());
								ca.setExtendedCAServiceData(type, data);
								log.info("Updating extended CA service of type "+type+" with implementation class "+OCSPCAService.class.getName());
								break;
							case ExtendedCAServiceTypes.TYPE_XKMSEXTENDEDSERVICE:
								data.put(ExtendedCAServiceInfo.IMPLEMENTATIONCLASS, XKMSCAService.class.getName());
								ca.setExtendedCAServiceData(type, data);
								log.info("Updating extended CA service of type "+type+" with implementation class "+XKMSCAService.class.getName());
								break;
							case ExtendedCAServiceTypes.TYPE_CMSEXTENDEDSERVICE:
								data.put(ExtendedCAServiceInfo.IMPLEMENTATIONCLASS, CmsCAService.class.getName());
								ca.setExtendedCAServiceData(type, data);
								log.info("Updating extended CA service of type "+type+" with implementation class "+CmsCAService.class.getName());
								break;
							case ExtendedCAServiceTypes.TYPE_HARDTOKENENCEXTENDEDSERVICE:
								data.put(ExtendedCAServiceInfo.IMPLEMENTATIONCLASS, HardTokenEncryptCAService.class.getName());
								ca.setExtendedCAServiceData(type, data);
								log.info("Updating extended CA service of type "+type+" with implementation class "+HardTokenEncryptCAService.class.getName());
								break;
							case ExtendedCAServiceTypes.TYPE_KEYRECOVERYEXTENDEDSERVICE:
								data.put(ExtendedCAServiceInfo.IMPLEMENTATIONCLASS, KeyRecoveryCAService.class.getName());
								ca.setExtendedCAServiceData(type, data);
								log.info("Updating extended CA service of type "+type+" with implementation class "+KeyRecoveryCAService.class.getName());
								break;
							default:
								break;
							}
						} else {
							// If we can't get info for the HardTokenEncrypt or KeyRecovery service it means they don't exist 
							// as such in the database, but was hardcoded before. We need to create them from scratch
							switch (type) {
							case ExtendedCAServiceTypes.TYPE_HARDTOKENENCEXTENDEDSERVICE:
								HardTokenEncryptCAServiceInfo htinfo = new HardTokenEncryptCAServiceInfo(ExtendedCAServiceInfo.STATUS_ACTIVE);
								HardTokenEncryptCAService htservice = new HardTokenEncryptCAService(htinfo);
								log.info("Creating extended CA service of type "+type+" with implementation class "+HardTokenEncryptCAService.class.getName());
								ca.setExtendedCAService(htservice);
								break;
							case ExtendedCAServiceTypes.TYPE_KEYRECOVERYEXTENDEDSERVICE:
								KeyRecoveryCAServiceInfo krinfo = new KeyRecoveryCAServiceInfo(ExtendedCAServiceInfo.STATUS_ACTIVE);
								KeyRecoveryCAService krservice = new KeyRecoveryCAService(krinfo);
								log.info("Creating extended CA service of type "+type+" with implementation class "+KeyRecoveryCAService.class.getName());
								ca.setExtendedCAService(krservice);
								break;
							default:
								break;
							}
						}
					}
					// If key recovery and hard token encrypt service does not exist, we have to create them
					CAInfo cainfo = ca.getCAInfo();
					Collection<ExtendedCAServiceInfo> extendedcaserviceinfos = new ArrayList<ExtendedCAServiceInfo>();
					if (!extendedServiceTypes.contains(ExtendedCAServiceTypes.TYPE_HARDTOKENENCEXTENDEDSERVICE)) {
						log.info("Adding new extended CA service of type "+ExtendedCAServiceTypes.TYPE_HARDTOKENENCEXTENDEDSERVICE+" with implementation class "+HardTokenEncryptCAService.class.getName());
						extendedcaserviceinfos.add(new HardTokenEncryptCAServiceInfo(ExtendedCAServiceInfo.STATUS_ACTIVE));
					}
					if (!extendedServiceTypes.contains(ExtendedCAServiceTypes.TYPE_KEYRECOVERYEXTENDEDSERVICE)) {
						log.info("Adding new extended CA service of type "+ExtendedCAServiceTypes.TYPE_KEYRECOVERYEXTENDEDSERVICE+" with implementation class "+KeyRecoveryCAService.class.getName());							
						extendedcaserviceinfos.add(new KeyRecoveryCAServiceInfo(ExtendedCAServiceInfo.STATUS_ACTIVE));
					}
					if (!extendedcaserviceinfos.isEmpty()) {
						cainfo.setExtendedCAServiceInfos(extendedcaserviceinfos);
						ca.updateCA(cainfo);
					}
					// Finally store the upgraded CA
					caSession.editCA(admin, ca, true);
				}
			} catch (CADoesntExistsException e) {
				log.error("CA does not exist during upgrade: "+caid, e);
			} catch (AuthorizationDeniedException e) {
				log.error("Authorization denied to CA during upgrade: "+caid, e);
			} catch (IllegalCryptoTokenException e) {
				log.error("Illegal Crypto Token editing CA during upgrade: "+caid, e);
			}
    	}
    	/*
    	 *  Upgrade super_administrator access rules to be a /* rule, so super_administrators can still do everything.
    	 *  
    	 * Also, set token types to the standard X500 principal if otherwise null. Since token types is a new concept, 
         * all existing aspects/admin entities must be of this type
    	 */
    	Collection<RoleData> roles = roleAccessSession.getAllRoles();
    	for (RoleData role : roles) {
    	    Collection<AccessUserAspectData> updatedUsers = new ArrayList<AccessUserAspectData>();
    	    for(AccessUserAspectData userAspect : role.getAccessUsers().values()) {
    	        if(userAspect.getTokenType() == null) {
    	            userAspect.setTokenType(X509CertificateAuthenticationToken.TOKEN_TYPE);
    	            updatedUsers.add(userAspect);
    	        }
    	    }
    	    try {
                role = roleMgmtSession.addSubjectsToRole(admin, role, updatedUsers);
            } catch (RoleNotFoundException e) {
                log.error("Not possible to edit subjects for role: "+role.getRoleName(), e);
            } catch (AuthorizationDeniedException e) {
                log.error("Not possible to edit subjects for role: "+role.getRoleName(), e);
            }
    
    	    
    		Map<Integer, AccessRuleData> rulemap = role.getAccessRules();
    		Collection<AccessRuleData> rules = rulemap.values();
    		for (AccessRuleData rule : rules) {
    			if (StringUtils.equals(AccessRulesConstants.ROLE_SUPERADMINISTRATOR, rule.getAccessRuleName()) && 
    					rule.getInternalState().equals(AccessRuleState.RULE_ACCEPT)) {
    				// Now we add a new rule
    		    	AccessRuleData slashrule = new AccessRuleData(role.getRoleName(), AccessRulesConstants.ROLE_ROOT, AccessRuleState.RULE_ACCEPT, true);
    		    	// Only add the rule if it does not already exist
    		    	if (!rulemap.containsKey(slashrule.getPrimaryKey())) {
        				log.info("Adding new access rule of '/' on role: "+role.getRoleName());
        	        	Collection<AccessRuleData> newrules = new ArrayList<AccessRuleData>();
        	        	newrules.add(slashrule);
        	    		try {
    						roleMgmtSession.addAccessRulesToRole(admin, role, newrules);
    					} catch (AccessRuleNotFoundException e) {
    						log.error("Not possible to add new access rule to role: "+role.getRoleName(), e);
    					} catch (RoleNotFoundException e) {
    						log.error("Not possible to add new access rule to role: "+role.getRoleName(), e);
    					} catch (AuthorizationDeniedException e) {
    						log.error("Not possible to add new access rule to role: "+role.getRoleName(), e);
    					}    		    		
    		    	}
					break; // no need to continue with this role
    			}
    		}
    	}
    	
    	accessTreeUpdateSession.signalForAccessTreeUpdate();
    	accessControlSession.forceCacheExpire();
    	
    	log.error("(this is not an error) Finished upgrade from ejbca 4.0.x to ejbca 5.0.x with result: "+ret);
        return ret;
    }

    /**
     * In EJBCA 5.0 we have changed classname for CertificatePolicy.
     * In order to allow us to remove the legacy class in the future we want to upgrade all certificate profiles to use the new classname
     * 
     * In order to be able to create new Roles we also need to remove the long deprecated database column caId, otherwise
     * we will get a database error during insert. Reading works fine though, so this is good for a post upgrade in order
     * to allow for 100% uptime upgrades.
     */
    private boolean postMigrateDatabase500(String dbtype) {

        log.error("(this is not an error) Starting post upgrade from ejbca 4.0.x to ejbca 5.0.x");
        boolean ret = true;

        AuthenticationToken admin = new AlwaysAllowLocalAuthenticationToken(new UsernamePrincipal("UpgradeSessionBean.migrateDatabase500"));

    	// post-upgrade "change CertificatePolicy from ejbca class to cesecore class in certificate profiles that have that defined.
        Map<Integer, String> map = certProfileSession.getCertificateProfileIdToNameMap();
        Set<Integer> ids = map.keySet();
        for (Integer id : ids) {
            CertificateProfile profile = certProfileSession.getCertificateProfile(id);
            final List<CertificatePolicy> policies = profile.getCertificatePolicies();
            if ((policies != null) && (!policies.isEmpty())) {
                List<CertificatePolicy> newpolicies = getNewPolicies(policies);
                // Set the updated policies, replacing the old
                profile.setCertificatePolicies(newpolicies);
                try {
                    final String profName = map.get(id);
                    log.info("Upgrading CertificatePolicy of certificate profile '"+profName+"'. This profile can no longer be used with EJBCA 4.x.");
                    certProfileSession.changeCertificateProfile(admin, profName, profile);
                } catch (AuthorizationDeniedException e) {
                    log.error("Error upgrading certificate policy: ", e);
                }
            }
            
        }
        // post-upgrade "change CertificatePolicy from ejbca class to cesecore class in CAs profiles that have that defined?
        // fix CAs that don't have classpath for extended CA services
        Collection<Integer> caids = caSession.getAvailableCAs();
        for (Integer caid : caids) {
            try {
                CA ca = caSession.getCAForEdit(admin, caid);
                if (ca.getCAType() == CAInfo.CATYPE_X509) {
                    try {
                        X509CA x509ca = (X509CA)ca;
                        final List<CertificatePolicy> policies = x509ca.getPolicies();
                        if ((policies != null) && (!policies.isEmpty())) {
                            List<CertificatePolicy> newpolicies = getNewPolicies(policies);
                            // Set the updated policies, replacing the old
                            x509ca.setPolicies(newpolicies);
                            // Finally store the upgraded CA
                            log.info("Upgrading CertificatePolicy of CA '"+ca.getName()+"'. This CA can no longer be used with EJBCA 4.x.");
                            caSession.editCA(admin, ca, true);
                        }
                    } catch (ClassCastException e) {
                        log.error("CA is not of type X509CA: "+caid+", "+ca.getClass().getName());
                    }
                }
            } catch (CADoesntExistsException e) {
                log.error("CA does not exist during upgrade: "+caid, e);
            } catch (AuthorizationDeniedException e) {
                log.error("Authorization denied to CA during upgrade: "+caid, e);
            } catch (IllegalCryptoTokenException e) {
                log.error("Illegal Crypto Token editing CA during upgrade: "+caid, e);
            }
        }
        
    	boolean exists = upgradeSession.checkColumnExists500();
    	if (exists) {
    		ret = migrateDatabase("/400_500/400_500-post-upgrade-"+dbtype+".sql");			
    	}

        // Creates a super admin role for Cli usage. post-upgrade to remove caId column must have been run in order
    	// for this command to succeed. 
    	// In practice this means that when upgrading from EJBCA 4.0 you can not use the CLI in 5.0 before you
    	// have finished migrating all your 4.0 nodes and run post-upgrade.
        complexAccessControlSession.createSuperAdministrator();
    	
    	log.error("(this is not an error) Finished post upgrade from ejbca 4.0.x to ejbca 5.0.x with result: "+ret);
        return ret;
    }

    private List<CertificatePolicy> getNewPolicies(final List<CertificatePolicy> policies) {
        final List<CertificatePolicy> newpolicies = new ArrayList<CertificatePolicy>();
        for(final Iterator<?> it = policies.iterator(); it.hasNext(); ) {
            Object o = it.next();
            try {
                final CertificatePolicy policy = (CertificatePolicy)o;
                // This was a new policy (org.cesecore), just add it
                newpolicies.add(policy);
            } catch (ClassCastException e) {
                // Here we stumbled upon an old certificate policy
                final org.ejbca.core.model.ca.certificateprofiles.CertificatePolicy policy = (org.ejbca.core.model.ca.certificateprofiles.CertificatePolicy)o;
                CertificatePolicy newpolicy = new CertificatePolicy(policy.getPolicyID(), policy.getQualifierId(), policy.getQualifier());
                newpolicies.add(newpolicy);                    
            }
        }
        return newpolicies;
    }

    /** 
     * Checks if the column cAId column exists in AdminGroupData
     * 
     * @return true or false if the column exists or not
     */
    public boolean checkColumnExists500() {
		// Try to find out if rowVersion exists and upgrade the PublisherQueueData in that case
		// This is needed since PublisherQueueData is a rather new table so it may have been created when the server started 
		// and we are upgrading from a not so new version
		final Connection connection = JDBCUtil.getDBConnection();
		boolean exists = false;
		try {
			final PreparedStatement stmt = connection.prepareStatement("select cAId from AdminGroupData where pk='0'");
			stmt.executeQuery();
			// If it did not throw an exception the column exists and we must run the post upgrade sql
			exists = true; 
			log.info("cAId column exists in AdminGroupData");
		} catch (SQLException e) {
			// Column did not exist
			log.info("cAId column does not exist in AdminGroupData");
			log.error(e);
		} finally {
			try {
				connection.close();
			} catch (SQLException e) {
				// do nothing
			}
		}
		return exists;
    }

}