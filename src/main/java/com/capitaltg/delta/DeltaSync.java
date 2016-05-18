package com.capitaltg.delta;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class DeltaSync {

	private static final Logger logger = LoggerFactory.getLogger(DeltaSync.class);
	private static final String DELTA_BANNER = 
		"\n(_) ___ __ _ _ __ ___     __| | ___| | |_ __ _ \n"+
		"| |/ __/ _` | '_ ` _ \\   / _` |/ _ \\ | __/ _` | \n"+
		"| | (_| (_| | | | | | | | (_| |  __/ | || (_| | \n"+
		"|_|\\___\\__,_|_| |_| |_|  \\__,_|\\___|_|\\__\\__,_| \n";
			
	public static void main(String[] args) throws Exception {
		DeltaSync deltaSync = new DeltaSync();
		deltaSync.startSync();
	}

	
	private void startSync() throws IOException{
		String configFile = "delta-config.xml";
		ApplicationContext context = new ClassPathXmlApplicationContext(configFile);
		logger.info(DELTA_BANNER);
		DeltaPoller poller = context.getBean(DeltaPoller.class);
		poller.setConversionMap(readConversionMap());
		Thread thread = new Thread(poller);
		thread.start();
		logger.info("Started DeltaSync");
	}

	private Map<String, String> readConversionMap() throws IOException {
		File file = new File("config/delta.properties");
		FileReader fileReader = new FileReader(file);
		Properties properties = new Properties();
		properties.load(fileReader);
		Map<String, String> map = new HashMap<>();
		properties.entrySet().forEach( e -> {
			if(e.getKey().toString().startsWith("map.")) {
				map.put(StringUtils.substringAfter(e.getKey().toString(),"map."), e.getValue().toString());
			}
		});
		fileReader.close();
		return map;
	}
	
}
