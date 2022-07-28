package com.equensworldline.amq.utils.json;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Set;

public class MyJSONObject {

    // Wrapper for json to make the interface more simple....

    String decFormatString = "%d";
    String floatFormatString = "%1.1f";

    JSONObject json;
    Iterator<String> keys;

    public MyJSONObject() {

        json = new JSONObject();
        keys = json.keys();
    }

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

    public void printString() {
        printString(json);
    }

    public Object next() {
        Object o = keys.next();
        if (o == null) {
            return null;
        }
        String type = o.getClass().getSimpleName();
        if (type.startsWith("JSONObject")) {
            return new MyJSONObject((JSONObject) o);
        }
        if (type.startsWith("JSONArray")) {
            return new MyJSONArray((JSONArray) o);
        }

        return o;
    }

    public boolean hasNext() {
        return keys.hasNext();
    }

    public int length() {
        return json.length();
    }

    public Object get(String key) {
        String type = getType(key);
        if (type == null) {
            return null;
        }

        if (type.startsWith("JSONObject")) {
            return new MyJSONObject((JSONObject) json.get(key));
        }
        if (type.startsWith("JSONArray")) {
            return new MyJSONArray((JSONArray) json.get(key));
        }
        return json.get(key);
    }

    public String getType(String key) {
        Object o = null;
        try {
            o = json.get(key);
        } catch (JSONException e) {
        }
        ;
        return o == null ? null : o.getClass().getSimpleName();
    }

    public String getString(String key) {
        String type = getType(key);
        if (type == null) {
            return null;
        }
        if (type.startsWith("JSON")) {
            return json.get(key).toString();
        }
        if (type.equals("Integer")) {
            return String.format(decFormatString, (Integer) json.get(key));
        }
        if (type.equals("Double")) {
            return String.format(floatFormatString, (Double) json.get(key));
        }
        if (type.equals("Float")) {
            return String.format(floatFormatString, (Float) json.get(key));
        }
        if (type.equals("Long")) {
            return String.format(decFormatString, (Long) json.get(key));
        }
        if (type.equals("Boolean")) {
            return ((Boolean) json.get(key)).toString();
        }
        return type.equals("Null") ? "" : (String) json.get(key);
    }

    public JSONObject getJSONObject() {
    	return json;
    }
    
    public Integer getInteger(String key) {
        String type = getType(key);
        if (type.equals("Integer")) {
            return (Integer) json.get(key);
        }
        return null;
    }

    public Long getLong(String key) {
        String type = getType(key);
        if (type.equals("Long")) {
            return (Long) json.get(key);
        }
        return null;
    }

    public Float getFloat(String key) {
        String type = getType(key);
        if (type.equals("Float")) {
            return (Float) json.get(key);
        }
        return null;
    }

    public Boolean getBoolean(String key) {
        String type = getType(key);
        if (type.equals("Boelean")) {
            return (Boolean) json.get(key);
        }
        return null;
    }

    public Double getDouble(String key) {
        String type = getType(key);
        if (type.equals("Double")) {
            return (Double) json.get(key);
        }
        return null;
    }

    public Object find(String key) {
        String type = findType(key);
        if (type == null) {
            return null;
        }
        if (type.startsWith("JSONObject")) {
            return new MyJSONObject((JSONObject) find(key, json));
        }
        return find(key, json);
    }

    public String findType(String key) {
        Object o = find(key, json);
        return o == null ? null : o.getClass().getSimpleName();
    }

    public String findString(String key) {
        String type = findType(key);
        if (type == null) {
            return null;
        }
        if (type.startsWith("JSON")) {
            return find(key, json).toString();
        }
        if (type.equals("Integer")) {
            return ((Integer) find(key, json)).toString();
        }
        if (type.equals("Long")) {
            return ((Long) json.get(key)).toString();
        }
        return type.equals("Null") ? "" : (String) find(key, json);
    }
    
    public void append(String key, Object obj) {
        json.append(key,obj);
    }

    public Set keySet() {
        return json.keySet();
    }

    public void setDecimalFormat(String decFormatStr) {
        decFormatString = decFormatStr;
    }

    public void setFloatFormat(String floatFormatStr) {
        floatFormatString = floatFormatStr;
    }

    private void printString(JSONObject json) {
        Set<String> keySet = json.keySet();
        for (String key : keySet) {
            Object value = json.get(key);
            System.out.printf("%s=%s (%s)\n", key, value, value.getClass().getSimpleName());
            String type = value.getClass().getSimpleName();
            if (type.equals("JSONObject")) {
                printString((JSONObject) value);
            }
        }
    }

    private Object find(String id, JSONObject json) {

        Set<String> keySet = json.keySet();
        for (String key : keySet) {
            Object value = json.get(key);
            if (key.equals(id)) {
                return value;
            }
            String type = value.getClass().getSimpleName();
            if (type.equals("JSONObject")) {
                Object v = find(id, (JSONObject) value);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }
}
