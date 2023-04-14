package com.equensworldline.amq.utils.json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

public class MyJSONArray {

    private JSONArray jsona;
    private Iterator<Object> vals;

    public MyJSONArray() {
        jsona = new JSONArray();
        vals = null;
    }

    public MyJSONArray(String jsonStr) {
        jsona = new JSONArray(jsonStr);
        vals = null;
    }

    public MyJSONArray(JSONArray ja) {
        jsona = ja;
        vals = null;
    }

    public int length() {
        return jsona.length();
    }

    public Object get(int index) {
        Object o = jsona.get(index);
        String type = o.getClass().getSimpleName();
        if (type.startsWith("JSONObject")) {
            return new MyJSONObject((JSONObject) o);
        }
        if (type.startsWith("JSONArray")) {
            return new MyJSONArray((JSONArray) o);
        }
        return o;
    }

    public void put(MyJSONObject obj) {
        jsona.put(obj.getJSONObject());
    }

    public Object next() {
        if (vals == null) {
            vals = jsona.iterator();
        }
        Object o = vals.next();
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
        if (vals == null) {
            vals = jsona.iterator();
        }
        return vals.hasNext();
    }

}
