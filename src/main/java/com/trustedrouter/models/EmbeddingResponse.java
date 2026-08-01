package com.trustedrouter.models;

import com.google.gson.JsonElement;
import java.util.Collections;
import java.util.List;

/** OpenAI-compatible embeddings response. */
public final class EmbeddingResponse extends JsonModel {
    private String object;
    private List<Embedding> data;
    private String model;
    private ChatCompletion.Usage usage;
    public String getObject() { return object; }
    public List<Embedding> getData() {
        return data == null ? Collections.<Embedding>emptyList() : Collections.unmodifiableList(data);
    }
    public String getModel() { return model; }
    public ChatCompletion.Usage getUsage() { return usage; }

    public static final class Embedding {
        private int index;
        private String object;
        private JsonElement embedding;
        public int getIndex() { return index; }
        public String getObject() { return object; }
        public JsonElement getEmbedding() { return embedding; }
    }
}
