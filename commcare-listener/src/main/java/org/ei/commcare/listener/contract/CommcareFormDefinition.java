package org.ei.commcare.listener.contract;

import java.util.HashMap;

public class CommcareFormDefinition {
    private CommCareExportUrl url;
    private String name;
    private final HashMap<String, String> mappings;

    public CommcareFormDefinition(String name, CommCareExportUrl url, HashMap<String, String> mappings) {
        this.url = url;
        this.name = name;
        this.mappings = mappings;
    }

    public String url() {
        return url.url();
    }

    public String name() {
        return name;
    }

    public HashMap<String, String> mappings() {
        return mappings;
    }

}
