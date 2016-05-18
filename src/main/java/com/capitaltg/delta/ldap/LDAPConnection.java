package com.capitaltg.delta.ldap;

import java.io.IOException;
import java.util.Map;

import javax.naming.NamingException;
import javax.naming.directory.SearchResult;

public interface LDAPConnection {
	public void syncAllUsers(LDAPConnection connection, long timestamp, Map<String, String> conversionMap) throws NamingException, IOException;
	public void syncEntry(String id, SearchResult result, Map<String, String> conversionMap) throws NamingException;
}