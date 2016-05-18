package com.capitaltg.delta.util;

import java.util.HashMap;
import java.util.Locale;

public class CaseInsensitiveMap extends HashMap<String, Object> {

	@Override
	public Object put(String key, Object object) {
		return super.put(key.toLowerCase(Locale.US), object);
	}

	@Override
	public Object get(Object key) {
		return super.get(key.toString().toLowerCase(Locale.US));
	}
}
