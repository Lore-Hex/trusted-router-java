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
    /**
     * Returns object.
     *
     * @return the object
     */
    public String getObject() { return object; }
    /**
     * Returns data.
     *
     * @return the data
     */
    public List<Embedding> getData() {
        return data == null ? Collections.<Embedding>emptyList() : Collections.unmodifiableList(data);
    }
    /**
     * Returns model.
     *
     * @return the model
     */
    public String getModel() { return model; }
    /**
     * Returns usage.
     *
     * @return the usage
     */
    public ChatCompletion.Usage getUsage() { return usage; }

    /**
     * Represents embedding.
     */
    public static final class Embedding {
        private int index;
        private String object;
        private JsonElement embedding;
        /**
         * Returns index.
         *
         * @return the index
         */
        public int getIndex() { return index; }
        /**
         * Returns object.
         *
         * @return the object
         */
        public String getObject() { return object; }
        /**
         * Returns embedding.
         *
         * @return the embedding
         */
        public JsonElement getEmbedding() { return embedding; }
    }
}
