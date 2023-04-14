package com.equensworldline.amq.utils.json;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Set;

public class MyJSONObject {

    public static final Logger logger = LoggerFactory.getLogger(MyJSONObject.class);

    // Wrapper for json to make the interface more simple....

    private String decimalFormat = "%d";
    private String floatFormat = "%1.1f";

    private final JSONObject json;
    private final Iterator<String> keys;

    public MyJSONObject(JSONObject jo) {
        json = jo;
        keys = json.keys();
    }

    public MyJSONObject(String jsonText) {
        json = new JSONObject(jsonText);
        keys = json.keys();
    }

    public String toString() {
        return json.toString();
    }

    public String toString(int indent) {
        return json.toString(indent);
    }

    public String next() {
        return keys.next();
    }

    public boolean hasNext() {
        return keys.hasNext();
    }

    public int length() {
        return json.length();
    }

    public Object get(String key) {
        Object result = json.opt(key);
        if (result instanceof JSONObject) {
            result = new MyJSONObject((JSONObject) result);
        } else if (result instanceof JSONArray) {
            result = new MyJSONArray((JSONArray) result);
        }
        return result;
    }

    public String getString(String key) {
        Object value = json.opt(key);
        return nullableValueToNullableString(value);
    }

    public JSONObject getJSONObject() {
        return json;
    }

    public Integer getInteger(String key) {
        Object value = json.opt(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return null;
    }

    public Long getLong(String key) {
        Object value = json.opt(key);
        if (value instanceof Long) {
            return (Long) value;
        }
        return null;
    }

    public Object find(String key) {
        Object result = find(key, json);
        if (result instanceof JSONObject) {
            result = new MyJSONObject((JSONObject) result);
        }
        return result;
    }

    public String findString(String key) {
        Object value = find(key, json);
        return nullableValueToNullableString(value);
    }

    private String nullableValueToNullableString(Object value) {
        String result;
        if (value instanceof Integer || value instanceof Long) {
            result = String.format(decimalFormat, value);
        } else if (value instanceof Double || value instanceof Float) {
            result = String.format(floatFormat, value);
        } else if (value == JSONObject.NULL) {
            result = "";
        } else {
            result = value == null ? null : value.toString();
        }
        return result;
    }

    public void append(String key, Object obj) {
        json.append(key, obj);
    }

    public Set keySet() {
        return json.keySet();
    }

    public void setDecimalFormat(String decFormatStr) {
        decimalFormat = decFormatStr;
    }

    public void setFloatFormat(String floatFormatStr) {
        floatFormat = floatFormatStr;
    }

    private Object find(String id, JSONObject json) {

        Set<String> keySet = json.keySet();
        for (String key : keySet) {
            Object value = json.get(key);
            if (key.equals(id)) {
                return value;
            }
            String type = value.getClass().getSimpleName();
            if ("JSONObject".equals(type)) {
                Object v = find(id, (JSONObject) value);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }
}
