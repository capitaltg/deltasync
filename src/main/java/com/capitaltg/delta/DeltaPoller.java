package com.capitaltg.delta;

import java.io.IOException;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.naming.NamingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.capitaltg.delta.ldap.LDAPConnection;

public class DeltaPoller implements Runnable {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private LDAPConnection sourceConnection;
	private LDAPConnection destinationConnection;
	private Map<String, String> conversionMap;
	
	private long secondsBetweenSyncs;
	private long secondsSinceChanged;
	private boolean doFullSyncFirst;
	
	public DeltaPoller(LDAPConnection source, LDAPConnection destination) {
		this.sourceConnection = source;
		this.destinationConnection = destination;
	}

	@PostConstruct
	public void init() {
		logger.info("Initialized DeltaPoller");
		logger.info("  Will poll LDAP source every {} seconds", secondsBetweenSyncs);
		logger.info("  Will poll LDAP source every for changes in the last {} seconds", secondsSinceChanged);
		if(doFullSyncFirst) {
			logger.info("  Will run initial full sync");
		}
	}
	
	@Override
	public void run() {
		
		if(doFullSyncFirst) {
			try {
				sourceConnection.syncAllUsers(destinationConnection, 0L, conversionMap);
			} catch (NamingException | IOException e) {
				logger.error("Failed while synchronizing all users",e);
			}
		}
		
		while(true) {
			try {
				long startTime = System.currentTimeMillis() - (secondsSinceChanged*1000);
				sourceConnection.syncAllUsers(destinationConnection, startTime, conversionMap);
				logger.debug("Synchronized all objects");
				Thread.sleep(secondsBetweenSyncs*1000);
			} catch (InterruptedException e) {
				logger.error("Failed while synchronizing users",e);
			} catch (NamingException e) {
				logger.error("Failed while synchronizing users",e);
			} catch (IOException e) {
				logger.error("Failed while synchronizing users",e);
			}
		}
		
	}

	public void setConversionMap(Map<String, String> conversionMap) {
		this.conversionMap = conversionMap;
	}

	public void setSecondsBetweenSyncs(long secondsBetweenSyncs) {
		this.secondsBetweenSyncs = secondsBetweenSyncs;
	}

	public void setSecondsSinceChanged(long secondsSinceChanged) {
		this.secondsSinceChanged = secondsSinceChanged;
	}

	public void setDoFullSyncFirst(boolean doFullSyncFirst) {
		this.doFullSyncFirst = doFullSyncFirst;
	}
	
}
