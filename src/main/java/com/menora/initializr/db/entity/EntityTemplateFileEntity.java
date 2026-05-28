package com.menora.initializr.db.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One template file inside an {@link EntityTemplateSetEntity}. Both {@code pathTemplate}
 * and {@code content} are rendered through Mustache when {@code substitutionType=MUSTACHE}.
 *
 * <p>When {@code perEntity=true}, this file is rendered once per user-supplied entity
 * (e.g. {@code UserController.java}, {@code OrderController.java}). When false, it is
 * rendered once total (shared infrastructure like {@code package.json} or {@code App.tsx}).
 */
@Entity
@Table(name = "entity_template_file")
public class EntityTemplateFileEntity {

    public enum FileType {
        TEMPLATE,
        STATIC_COPY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "set_id", nullable = false)
    private Long setId;

    @NotBlank @Size(max = 500)
    @Column(name = "path_template", nullable = false, length = 500)
    private String pathTemplate;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String content;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "substitution_type", nullable = false, length = 20)
    private FileContributionEntity.SubstitutionType substitutionType = FileContributionEntity.SubstitutionType.MUSTACHE;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 20)
    private FileType fileType = FileType.TEMPLATE;

    @Column(name = "per_entity", nullable = false)
    private boolean perEntity = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSetId() { return setId; }
    public void setSetId(Long setId) { this.setId = setId; }
    public String getPathTemplate() { return pathTemplate; }
    public void setPathTemplate(String pathTemplate) { this.pathTemplate = pathTemplate; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public FileContributionEntity.SubstitutionType getSubstitutionType() { return substitutionType; }
    public void setSubstitutionType(FileContributionEntity.SubstitutionType substitutionType) { this.substitutionType = substitutionType; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }
    public boolean isPerEntity() { return perEntity; }
    public void setPerEntity(boolean perEntity) { this.perEntity = perEntity; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
