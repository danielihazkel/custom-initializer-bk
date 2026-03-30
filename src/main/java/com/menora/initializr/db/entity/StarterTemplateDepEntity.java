package com.menora.initializr.db.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "starter_template_dep")
public class StarterTemplateDepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer"})
    private StarterTemplateEntity template;

    @NotBlank @Size(max = 50)
    @Column(name = "dep_id", nullable = false, length = 50)
    private String depId;

    @Column(name = "sub_options", length = 500)
    private String subOptions;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public StarterTemplateEntity getTemplate() { return template; }
    public void setTemplate(StarterTemplateEntity template) { this.template = template; }
    public String getDepId() { return depId; }
    public void setDepId(String depId) { this.depId = depId; }
    public String getSubOptions() { return subOptions; }
    public void setSubOptions(String subOptions) { this.subOptions = subOptions; }
}
