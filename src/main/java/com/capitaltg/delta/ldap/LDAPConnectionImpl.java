package com.capitaltg.delta.ldap;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.AttributeInUseException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SchemaViolationException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.LdapReferralException;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.capitaltg.delta.util.CaseInsensitiveMap;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import groovy.lang.GroovyShell;

/**
 * @author tslazar
 *
 */
public class LDAPConnectionImpl implements LDAPConnection {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private Hashtable<String, String> context;
	private static Control[] PAGING_CONTROLS;
	private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss.0'Z'");
	
	private String ldapusername;
	private String ldappassword;
	private String ldapurl;
	private String ldapbasedn;
	private String uniqueid;
	private List<String> objectclass;
	private String extrafilter;
	private boolean doNotRepeatFailures = true;
	private Set<String> failedCreations = new HashSet<>();
	private int pageSize = 10;

	private LDAPConnection sourceConnection;
	private boolean readonly = true;
	
	@PostConstruct
	public void init() {

		logger.info("Read only is: {}",Boolean.valueOf(readonly));
		
		context=new Hashtable<>();
		context.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
		context.put(Context.SECURITY_PRINCIPAL, ldapusername);
		context.put(Context.SECURITY_CREDENTIALS, ldappassword);
		context.put(Context.PROVIDER_URL, ldapurl);
        context.put(Context.REFERRAL,"ignore");
		context.put("com.sun.jndi.ldap.connect.pool", "true");

		System.setProperty("com.sun.jndi.ldap.connect.pool.protocol","plain ssl");
		System.setProperty("com.sun.jndi.ldap.connect.pool.initsize","5");
		printSettings();
		dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		try {
			PAGING_CONTROLS = new Control[]{new PagedResultsControl(pageSize, Control.CRITICAL)};
		} catch (IOException e) {
			logger.error("Failed to initialize paging controls",e);
		}
		
	}
	
	private void printSettings() {
		logger.info("Initialized LDAPConnection with settings:");
		context.entrySet().stream().forEach( entry -> {
			logger.info("   "+entry.getKey()+ "\t"+ (entry.getKey().contains("credentials") ? "******" : entry.getValue()));
		});
	}
	
	@Override
	public void syncAllUsers(LDAPConnection connection, long timestamp, Map<String, String> conversionMap) throws NamingException, IOException {

		List<String> filters = new ArrayList<>();
		objectclass.stream().forEach( oc -> filters.add("(objectClass="+oc+")"));
		filters.add("(whenChanged>="+convertTimestampToWhen(timestamp)+")");
		if(!Strings.isNullOrEmpty(extrafilter)) {
			filters.add(extrafilter);
		}
		String filter = "(&"+filters.stream().collect(Collectors.joining())+")";
		logger.trace("Searching for objects matching: {}",filter);
		
		int counter = 0;
        LdapContext ldapcontext = null;
        NamingEnumeration<SearchResult> results = null;
        byte[] cookie = null;
        
        try {
        	
            ldapcontext = new InitialLdapContext(context, null);
            ldapcontext.setRequestControls(PAGING_CONTROLS);
	        ldapcontext.setRequestControls(new Control[]{
                    new PagedResultsControl(pageSize, cookie, Control.CRITICAL) });
	        
        	do {
		        SearchControls searchControls = new SearchControls();
		        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		        results = ldapcontext.search(ldapbasedn, filter, searchControls);
		        while(results.hasMoreElements()) {
		        	SearchResult result = results.nextElement();
		        	counter++;
		        	connection.syncEntry(getAttribute(result.getAttributes(), uniqueid), result, conversionMap);
		        }
		        cookie = getCookie(ldapcontext);
		        ldapcontext.setRequestControls(new Control[]{
                        new PagedResultsControl(pageSize, cookie, Control.CRITICAL) });
		        if(cookie!=null) {
		        	logger.debug("Read {} results.  Getting another page of results.",counter);
		        }
        	} while(cookie!=null);
        	
        } catch(LdapReferralException e) {
        	logger.error("Failed while following LDAP referral",e);
        	e.printStackTrace();
        } catch(NamingException e) {
        	logger.error("Failure while syncing users",e);
        	if(ldapcontext!=null) {
        		try {
        			ldapcontext.close();
        		} catch(NamingException ex) {
        			logger.error("Failed to close context after failure",e);
        		}
        	}
        } finally {
        	if(results!=null){
        		results.close();
        	}
        	if(ldapcontext!=null){
        		ldapcontext.close();
        	}
        }
    	logger.debug("Synchronized {} results",counter);

	}

	public void setLdapusername(String ldapusername) {
		this.ldapusername = ldapusername;
	}
	public void setLdappassword(String ldappassword) {
		this.ldappassword = ldappassword;
	}
	public void setLdapurl(String ldapurl) {
		this.ldapurl = ldapurl;
	}
	public void setLdapbasedn(String ldapbasedn) {
		this.ldapbasedn = ldapbasedn;
	}
	public void setUniqueid(String uniqueid) {
		this.uniqueid = uniqueid;
	}
	
	
	/* 
	 * Updates this LDAP from a foreign search result using 
	 * provided conversion map.  First checks for values in
	 * its own LDAP and then compares with provided mapping
	 * for update or create.
	 */
	@Override
	public void syncEntry(String id, SearchResult sourceEntry, Map<String, String> conversionMap) throws NamingException {
		SearchResult existingDestinationEntry = findEntry(id);
		if(existingDestinationEntry==null) {
			createEntry(id, sourceEntry, conversionMap);
		} else {
			updateEntry(id, sourceEntry, existingDestinationEntry, conversionMap);
		}
	}

	private Map<String,Object> extractAttributes(SearchResult result) {
		Map<String,Object> map = new CaseInsensitiveMap();
		NamingEnumeration<? extends Attribute> attributes = result.getAttributes().getAll();
		try {
			while(attributes.hasMoreElements()) {
				Attribute attribute = attributes.nextElement();
				map.put(attribute.getID(), getStringOrListValue(attribute));
			}
		} catch(NamingException e) {
			logger.error("Failed to map attribute map",e);
		}
		return map;
	}
	
	private Object getStringOrListValue(Attribute attribute) throws NamingException{
		if(attribute==null){
			return null;
		}
		List<Object> list = new ArrayList<>();
		NamingEnumeration<?> attributes = attribute.getAll();
		while(attributes.hasMoreElements()) {
			list.add(attributes.nextElement());
		}
		if(list.size()==0){
			return null;
		} else if(list.size()==1){
			return list.get(0);
		}
		return list;
	}
	
	private void updateEntry(String id, final SearchResult sourceEntry, final SearchResult existingDestinationEntry, Map<String, String> conversionMap) {

		try {
		logger.trace("Will try to update existing entry for {}",id);
		List<ModificationItem> modificationItems = new ArrayList<>();
		ModificationItem mi = getObjectClassUpdates(existingDestinationEntry);
		if(mi!=null){
			modificationItems.add(mi);
		}
		conversionMap.entrySet().stream().forEach( e -> {
				
			String sourceValue = runGroovy(
					ImmutableMap.of(
							"source",sourceEntry,
							"sourceConnection",sourceConnection,
							"destinationConnection",this,
							"attributes",extractAttributes(sourceEntry),
							"target",extractAttributes(existingDestinationEntry)), 
					e.getValue());
			
			String destinationValue = getAttribute(existingDestinationEntry.getAttributes(), e.getKey());
			if(sourceValue !=null) {
				if(Strings.isNullOrEmpty(destinationValue) && !Strings.isNullOrEmpty(sourceValue)) {
					logger.trace("Adding attribute {} to {}",sourceValue,e.getKey());
					modificationItems.add(new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute(e.getKey(), sourceValue)));
				} else if(!sourceValue.equals(destinationValue)) {
					logger.trace("Need to update {} to {}",destinationValue, sourceValue);
					modificationItems.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(e.getKey(), sourceValue)));
				}
			} else if(!Strings.isNullOrEmpty(destinationValue)){
				logger.trace("Need to delete {} since source is empty",destinationValue);
				modificationItems.add(new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(e.getKey(), sourceValue)));
			}
			
		});
		if(modificationItems.size()>0) {
			ModificationItem[] items = new ModificationItem[modificationItems.size()];
			modificationItems.toArray(items);
			updateObject(existingDestinationEntry.getNameInNamespace(), items);
			logger.debug("Updated {} with these updates: {}",id,items);
		}
		logger.trace("Finished updating entry for {}",id);
		} catch(Exception e) {
			logger.error("Failed to update "+id,e);
			if(doNotRepeatFailures) {
				failedCreations.add(id);
				logger.warn("Will not try again to update user id: {}", id);
			}
		}
	}

	private ModificationItem getObjectClassUpdates(final SearchResult existingDestinationEntry) throws NamingException{
		List<String> objectClasses = (List<String>)getStringOrListValue(existingDestinationEntry.getAttributes().get("objectClass"));
		objectClasses = objectClasses.stream().map( v -> v.toLowerCase()).collect(Collectors.toList());
		List<String> toHave = objectclass.stream().map( v -> v.toLowerCase()).collect(Collectors.toList());
		toHave.removeAll(objectClasses);
		if(toHave.size()>0) {
			objectClasses.addAll(toHave);
			BasicAttribute oc = new BasicAttribute("objectClass");
			objectClasses.stream().forEach( ocv -> oc.add(ocv));
			return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, oc);
		}
		return null;
	}
	
	private void updateObject(String objectDn, ModificationItem[] mods) throws NamingException {

		if(readonly) {
			logger.info("Read only mode: Will not update {} with {}", objectDn, mods);
			return;
		}
		
		DirContext ctx=new InitialDirContext(context);
		try {
			ctx.modifyAttributes(objectDn, mods);
		} finally {
			ctx.close();
		}
	}


	private String getAttribute(Attributes attributes, String name) {
		try {
			Attribute attribute = attributes.get(name);
			if(attribute==null){
				return null;
			}
			Object object = attribute.get();
			return object.toString();
		} catch(NamingException e) {
			logger.error("Failed to get attribute "+name,e);
			return null;
		}
	}

	private SearchResult findEntry(String uid) {
		
		String searchFilter = "("+uniqueid+"="+uid+")";
        LdapContext ldapContext = null;
        try {
        	SearchResult searchResult = null;
	        SearchControls searchControls = new SearchControls();
	        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            ldapContext = new InitialLdapContext(context, null);
            NamingEnumeration<SearchResult> results = ldapContext.search(ldapbasedn, searchFilter, searchControls);

	        if(results.hasMoreElements()) {
	        	searchResult = results.nextElement();
	            logger.debug("Found existing matching user for {}: {}",uid, searchResult.getNameInNamespace());
	        } 
	        ldapContext.close();
	        if(results.hasMoreElements()) {
	        	// TODO improve diagnosis
	        	logger.warn("User {} has more than one matching profile.  Updated first matching result {}", uid,searchResult.getNameInNamespace());
	        }
	        close(ldapContext);
	        return searchResult;

        } catch(NamingException e){
        	logger.error("Failed to find user due to NamingException",e);
        	if(ldapContext!=null) {
        		try {
        			ldapContext.close();
        		} catch(NamingException ex) {
        			logger.error("Failed to close context after failure",e);
        		}
        	}
            return null;
        } 
		
	}
	
	private void close(LdapContext context) {
		try {
			context.close();
		} catch(Exception e) {
			logger.error("Exception thrown while closing");
		}
	}

	public String createDNFromUniqueID(String uid) {
		return uniqueid+"="+uid + "," + ldapbasedn;
	}
	
	private void createEntry(String id, SearchResult result, Map<String, String> conversionMap) throws NamingException {

		logger.debug("Creating new entry for {}",id);
		if(doNotRepeatFailures && failedCreations.contains(id)) {
			logger.warn("Won't try to recreate failed: {}",id);
			return;
		}

		Attributes attributes = createAttributes(result, conversionMap);
		attributes.put(uniqueid, id);

		String dn = createDNFromUniqueID(id);
		
		if(readonly) {
			logger.info("Read only mode: Will not create user {}", dn);
			return;
		}
		
		DirContext ctx = null;
		Context newEntry = null;
		try {
			logger.info("Will create user {} with attributes {}", dn, attributes);
			ctx = new InitialDirContext(context);
			newEntry = ctx.createSubcontext(dn, attributes);
			newEntry.close();
			logger.info("Created user {}", dn);
		} catch (AttributeInUseException e) {
			logger.error("Failed to create user "+id, e);
		} catch(SchemaViolationException e) {
			logger.error("Failed to create user "+id, e);
			if(doNotRepeatFailures) {
				failedCreations.add(id);
				logger.warn("Will not try again to create user id: {}", id);
			}
		} finally {
			if (newEntry != null) {
				newEntry.close();
			}
			if (ctx != null) {
				ctx.close();
			}
		}

	}

	private Attributes createAttributes(SearchResult searchResult, Map<String, String> conversionMap) {
		Attributes attributes = new BasicAttributes(true);
		BasicAttribute oc = new BasicAttribute("objectClass");
		objectclass.stream().forEach( ocv -> oc.add(ocv));
		attributes.put(oc);
		
		conversionMap.entrySet().forEach( e -> {
			String string = runGroovy(
					ImmutableMap.of(
							"source",searchResult,
							"sourceConnection",sourceConnection,
							"destinationConnection",this,
							"attributes",extractAttributes(searchResult),
							"target",ImmutableMap.of()), 
					e.getValue());
			if(string!=null) {
				attributes.put(e.getKey(), string);
			}
		});
		
		return attributes;
	}

	public void setObjectclass(String objectclass) {
		this.objectclass = ImmutableList.copyOf(objectclass.split(","));
	}

	private String convertTimestampToWhen(long timestamp) {
		Date date = new Date(timestamp);
		return dateFormat.format(date);
	}

	public void setExtrafilter(String extrafilter) {
		this.extrafilter = extrafilter;
	}
	
	private static byte[] getCookie(LdapContext ldapcontext) throws NamingException{
		Control[] controls = ldapcontext.getResponseControls();
		for(Control control : controls) {
			if(control instanceof PagedResultsResponseControl) {
				PagedResultsResponseControl pagedResultsControl = (PagedResultsResponseControl)control;
				return pagedResultsControl.getCookie();
			}
		}
		return null;
	}

	public String runGroovy(Map<String,Object> map, String string) {
		GroovyShell shell = new GroovyShell();
		map.entrySet().forEach( e -> shell.setVariable(e.getKey(), e.getValue()));
		Object object = shell.evaluate(string);
//		logger.debug("Evaluated '{}' against {} and got {}",string,map,object);
		if(object==null){
			return null;
		}
		if(object instanceof Attribute) {
			Attribute attribute = (Attribute)object;
			try {
				return attribute.get().toString();
			} catch(NamingException e){
				return null;
			}
		}
		return object.toString();
	}

	public void setSourceConnection(LDAPConnection sourceConnection) {
		this.sourceConnection = sourceConnection;
	}

    private String getUniqueIDByDN(String dn) {
    	try {
	    	Attributes attributes = getDNAttributes(dn);
	    	return attributes.get(uniqueid).get().toString();
    	}catch(NamingException e) {
    		logger.error("Exception thrown while getting unique id by dn",e);
    		return null;
    	}
    }
	
    private Attributes getDNAttributes(String dn) {
    	logger.debug("Getting attributes for DN: {}",dn);
    	Attributes attributes = null;
    	LdapContext ldapContext = null;
		try {
			ldapContext = new InitialLdapContext(context, null);
	        SearchControls searchControls = new SearchControls();
	        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
	        attributes = ldapContext.getAttributes(dn);
			ldapContext.close();
		} catch(NameNotFoundException e) {
			logger.warn("Could not find attributes for entry with dn {}",dn);
		} catch(NamingException e){
			e.printStackTrace();
		}
		return attributes;
    }

	public void setReadonly(boolean readonly) {
		this.readonly = readonly;
	}

}
