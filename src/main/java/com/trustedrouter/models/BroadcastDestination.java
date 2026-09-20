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
    /**
     * Returns id.
     *
     * @return the id
     */
    public String getId() { return id; }
    /**
     * Returns type.
     *
     * @return the type
     */
    public String getType() { return type; }
    /**
     * Returns name.
     *
     * @return the name
     */
    public String getName() { return name; }
    /**
     * Returns endpoint.
     *
     * @return the endpoint
     */
    public String getEndpoint() { return endpoint; }
    /**
     * Returns enabled.
     *
     * @return the enabled
     */
    public Boolean getEnabled() { return enabled; }
    /**
     * Returns include content.
     *
     * @return the include content
     */
    public Boolean getIncludeContent() { return includeContent; }
    /**
     * Returns method.
     *
     * @return the method
     */
    public String getMethod() { return method; }
    /**
     * Returns headers.
     *
     * @return the headers
     */
    public Map<String, String> getHeaders() {
        return headers == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(headers);
    }
}
