package com.company.warden.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "vector_store", schema = "sop")
public class VectorStoreEntity {

    @Id
    private UUID id;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    public VectorStoreEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
