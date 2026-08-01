package com.trustedrouter.models;

import java.util.Collections;
import java.util.Map;

/** Redacted workspace Broadcast destination. */
public final class BroadcastDestination extends JsonModel {
    private String id;
    private String type;
    private String name;
    private String endpoint;
    private Boolean enabled;
    private Boolean includeContent;
    private String method;
    private Map<String, String> headers;
    public String getId() { return id; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public Boolean getEnabled() { return enabled; }
    public Boolean getIncludeContent() { return includeContent; }
    public String getMethod() { return method; }
    public Map<String, String> getHeaders() {
        return headers == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(headers);
    }
}
